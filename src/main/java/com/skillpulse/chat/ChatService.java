package com.skillpulse.chat;

import com.skillpulse.ai.AiInsightsService;
import com.skillpulse.auth.AppUser;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.personalization.LearningPreference;
import com.skillpulse.personalization.LearningPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The study chat: saved conversations, per-learner limits and the relay to the Python ml-service.
 *
 * Rules it follows (same as AiInsightsService):
 *  - No AI logic here. Routing, the scoped prompt and the model calls all live in ml-service.
 *  - The history the model sees is read from THIS database, never taken from the browser.
 *  - Database work happens in short transactions; the (slow) ML call happens outside any transaction.
 *  - A message whose reply failed completely is removed again, so a broken turn never pollutes the history.
 */
@Service
public class ChatService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int MAX_MESSAGE_CHARS = 1200;
    static final int HISTORY_TURNS = 8;
    static final int MAX_CONVERSATIONS = 100;
    static final int MAX_MESSAGES_PER_CONVERSATION = 400;
    static final int MAX_REPLY_CHARS = 12000;
    static final long STREAM_TIMEOUT_MS = 130000L;
    static final String UNAVAILABLE = "The study coach is unavailable right now. Please try again in a moment.";
    static final List<String> REFUSED_CATEGORIES = Arrays.asList("OFF_TOPIC", "MIXED");

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AiInsightsService ai;
    private final LearningPreferenceRepository preferences;
    private final MlServiceClient ml;
    private final ChatRateLimiter limiter;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final ThreadPoolExecutor executor;

    public ChatService(ChatConversationRepository conversations, ChatMessageRepository messages,
                       AiInsightsService ai, LearningPreferenceRepository preferences, MlServiceClient ml,
                       ChatRateLimiter limiter, PlatformTransactionManager txManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.ai = ai;
        this.preferences = preferences;
        this.ml = ml;
        this.limiter = limiter;
        this.write = new TransactionTemplate(txManager);
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        this.read = readOnly;

        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread t = new Thread(task, "chat-stream-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = new ThreadPoolExecutor(8, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), threads, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    // ==== a message that was accepted and saved, waiting for its reply =================================

    static final class Prepared {
        final AppUser user;
        final Long conversationId;
        final String title;
        final boolean created;
        final Long userMessageId;
        final String message;
        final List<Map<String, Object>> history;
        final Map<String, Object> context;

        Prepared(AppUser user, Long conversationId, String title, boolean created, Long userMessageId,
                 String message, List<Map<String, Object>> history, Map<String, Object> context) {
            this.user = user;
            this.conversationId = conversationId;
            this.title = title;
            this.created = created;
            this.userMessageId = userMessageId;
            this.message = message;
            this.history = history;
            this.context = context;
        }

        Map<String, Object> mlBody() {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("message", message);
            body.put("history", history);
            body.put("context", context);
            return body;
        }
    }

    /** Validates and saves the learner's message, and reads the stored history. Throws IllegalArgumentException (400). */
    Prepared prepare(final AppUser user, final Long conversationId, String rawMessage) {
        final String message = cleanMessage(rawMessage);
        final Map<String, Object> context = context(user);
        return write.execute(status -> {
            ChatConversation conv;
            boolean created = false;
            if (conversationId == null) {
                if (conversations.countByUserId(user.getId()) >= MAX_CONVERSATIONS) {
                    throw new IllegalArgumentException(
                            "You have reached the limit of " + MAX_CONVERSATIONS + " saved chats. Delete an old chat to start a new one.");
                }
                conv = new ChatConversation();
                conv.setUser(user);
                conv.setTitle(titleFrom(message));
                conv = conversations.save(conv);
                created = true;
            } else {
                conv = conversations.findByIdAndUserId(conversationId, user.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Conversation not found."));
                if (messages.countByConversationId(conv.getId()) >= MAX_MESSAGES_PER_CONVERSATION) {
                    throw new IllegalArgumentException("This chat is full. Start a new chat to keep going.");
                }
            }

            List<ChatMessage> recent = new ArrayList<ChatMessage>(
                    messages.findByConversationIdOrderByIdDesc(conv.getId(), PageRequest.of(0, HISTORY_TURNS)));
            Collections.reverse(recent);
            List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
            for (ChatMessage m : recent) {
                Map<String, Object> turn = new LinkedHashMap<String, Object>();
                turn.put("role", m.getRole());
                turn.put("content", clip(m.getContent(), 1500));
                history.add(turn);
            }

            ChatMessage saved = new ChatMessage();
            saved.setConversation(conv);
            saved.setRole(ChatMessage.USER);
            saved.setContent(message);
            saved = messages.save(saved);
            conv.setUpdatedAt(Instant.now());
            conversations.save(conv);
            return new Prepared(user, conv.getId(), conv.getTitle(), created, saved.getId(), message, history, context);
        });
    }

    /** Saves the assistant reply and the router's label on the learner message. */
    void complete(final Prepared p, final String category, final String reply, final String engine, final String status) {
        try {
            write.execute(tx -> {
                ChatConversation conv = conversations.findById(p.conversationId).orElse(null);
                if (conv == null) return null; // the learner deleted the chat while the reply was being written
                if (category != null && !category.isEmpty()) {
                    ChatMessage learnerMessage = messages.findById(p.userMessageId).orElse(null);
                    if (learnerMessage != null) {
                        learnerMessage.setCategory(clip(category, 16));
                        messages.save(learnerMessage);
                    }
                }
                ChatMessage answer = new ChatMessage();
                answer.setConversation(conv);
                answer.setRole(ChatMessage.ASSISTANT);
                answer.setContent(reply);
                answer.setEngine(engine == null ? null : clip(engine, 120));
                answer.setStatus(status);
                messages.save(answer);
                conv.setUpdatedAt(Instant.now());
                conversations.save(conv);
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("Could not save the chat reply for conversation {}", p.conversationId, ex);
        }
    }

    /** Removes a learner message whose reply never arrived (and its conversation if that was the only message). */
    void discard(final Prepared p) {
        try {
            write.execute(tx -> {
                if (p.created) {
                    conversations.findById(p.conversationId).ifPresent(conversations::delete);
                } else {
                    messages.findById(p.userMessageId).ifPresent(messages::delete);
                }
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("Could not discard the failed chat message {}", p.userMessageId, ex);
        }
    }

    // ==== sending: streaming ===========================================================================

    /**
     * Accepts a message and streams the reply. Runs on the request thread up to the point where the message is saved
     * (so validation and limit errors become normal JSON errors), then hands the slow part to a worker thread.
     */
    public SseEmitter startStream(AppUser user, Long conversationId, String message) {
        limiter.checkMessage(user.getId());
        limiter.acquireStream(user.getId());
        final Prepared p;
        try {
            p = prepare(user, conversationId, message);
        } catch (RuntimeException ex) {
            limiter.releaseStream(user.getId());
            throw ex;
        }

        final SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
        try {
            executor.execute(() -> runStream(p, emitter, cancelled));
        } catch (RejectedExecutionException ex) {
            discard(p);
            limiter.releaseStream(user.getId());
            throw new ChatLimitException(503, "The study coach is busy right now. Please try again in a moment.", 5);
        }
        return emitter;
    }

    private void runStream(final Prepared p, final SseEmitter emitter, final AtomicBoolean cancelled) {
        final StringBuilder reply = new StringBuilder();
        final String[] category = new String[1];
        final String[] engine = new String[1];
        final String[] error = new String[1];
        final boolean[] finished = new boolean[1];
        try {
            Map<String, Object> start = new LinkedHashMap<String, Object>();
            start.put("conversationId", p.conversationId);
            start.put("title", p.title);
            start.put("isNew", p.created);
            if (!send(emitter, "start", start)) cancelled.set(true);

            if (!cancelled.get()) {
                boolean connected = ml.streamChat(p.mlBody(), event -> {
                    if (cancelled.get()) return false;
                    String type = str(event.get("type"));
                    boolean ok = true;
                    if ("meta".equals(type)) {
                        category[0] = clip(str(event.get("category")), 16);
                        ok = send(emitter, "meta", Collections.singletonMap("category", category[0]));
                    } else if ("delta".equals(type)) {
                        String text = str(event.get("text"));
                        if (text.isEmpty()) return true;
                        reply.append(text);
                        ok = send(emitter, "delta", Collections.singletonMap("text", text));
                        if (ok && reply.length() >= MAX_REPLY_CHARS) {
                            finished[0] = true; // absurdly long: keep what we have and stop
                            return false;
                        }
                    } else if ("done".equals(type)) {
                        engine[0] = str(event.get("engine"));
                        if (category[0] == null) category[0] = clip(str(event.get("category")), 16);
                        finished[0] = true;
                        return false;
                    } else if ("error".equals(type)) {
                        error[0] = str(event.get("message"));
                        return false;
                    }
                    if (!ok) cancelled.set(true);
                    return ok;
                });
                if (!connected && error[0] == null && reply.length() == 0) error[0] = UNAVAILABLE;
            }
        } catch (RuntimeException ex) {
            log.error("Chat stream failed for conversation {}", p.conversationId, ex);
            error[0] = UNAVAILABLE;
        }

        try {
            String text = reply.toString().trim();
            boolean gone = cancelled.get();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("conversationId", p.conversationId);
            result.put("title", p.title);
            result.put("category", category[0]);
            if (finished[0] && !text.isEmpty()) {
                complete(p, category[0], text, engine[0], ChatMessage.OK);
                result.put("engine", engine[0]);
                if (!gone) send(emitter, "done", result);
            } else if (!text.isEmpty()) {
                // The learner pressed Stop, left, or the model failed half way: keep the partial answer.
                complete(p, category[0], text, engine[0], ChatMessage.STOPPED);
                if (!gone) {
                    result.put("message", error[0] == null ? "The reply was interrupted." : error[0]);
                    result.put("partial", true);
                    send(emitter, "error", result);
                }
            } else {
                discard(p);
                if (!gone) {
                    result.put("message", error[0] == null ? UNAVAILABLE : error[0]);
                    result.put("partial", false);
                    send(emitter, "error", result);
                }
            }
        } catch (RuntimeException ex) {
            log.error("Chat stream cleanup failed for conversation {}", p.conversationId, ex);
        } finally {
            limiter.releaseStream(p.user.getId());
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed because the client went away
            }
        }
    }

    // ==== sending: one complete reply (no streaming) =====================================================

    /** Same flow without streaming. Returns null when the ML service could not answer. */
    public Map<String, Object> sendBlocking(AppUser user, Long conversationId, String message) {
        limiter.checkMessage(user.getId());
        limiter.acquireStream(user.getId());
        Prepared p = null;
        try {
            p = prepare(user, conversationId, message);
            Map<String, Object> result = ml.callSlow("/chat/reply", p.mlBody());
            String reply = result == null ? "" : str(result.get("reply")).trim();
            if (reply.isEmpty()) {
                discard(p);
                return null;
            }
            String category = clip(str(result.get("category")), 16);
            complete(p, category, reply, str(result.get("engine")), ChatMessage.OK);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("conversationId", p.conversationId);
            out.put("title", p.title);
            out.put("reply", reply);
            out.put("category", category);
            out.put("engine", result.get("engine"));
            return out;
        } catch (RuntimeException ex) {
            if (p != null) discard(p);
            throw ex;
        } finally {
            limiter.releaseStream(user.getId());
        }
    }

    // ==== learner: saved conversations =================================================================

    public List<Map<String, Object>> listConversations(final AppUser user) {
        return read.execute(tx -> {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (ChatConversation c : conversations.findByUserIdOrderByUpdatedAtDesc(
                    user.getId(), PageRequest.of(0, MAX_CONVERSATIONS))) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", c.getId());
                row.put("title", c.getTitle());
                row.put("updatedAt", c.getUpdatedAt().toString());
                out.add(row);
            }
            return out;
        });
    }

    public Map<String, Object> getConversation(final AppUser user, final Long id) {
        return read.execute(tx -> {
            ChatConversation c = conversations.findByIdAndUserId(id, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found."));
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (ChatMessage m : messages.findByConversationIdOrderByIdAsc(c.getId())) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", m.getId());
                row.put("role", m.getRole());
                row.put("content", m.getContent());
                row.put("stopped", ChatMessage.STOPPED.equals(m.getStatus()));
                row.put("createdAt", m.getCreatedAt().toString());
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("id", c.getId());
            out.put("title", c.getTitle());
            out.put("messages", rows);
            return out;
        });
    }

    public void deleteConversation(final AppUser user, final Long id) {
        write.execute(tx -> {
            ChatConversation c = conversations.findByIdAndUserId(id, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found."));
            conversations.delete(c);
            return null;
        });
    }

    // ==== admin: what the router refused ===================================================================

    public Map<String, Object> moderationSummary() {
        return read.execute(tx -> {
            Map<String, Long> counts = new LinkedHashMap<String, Long>();
            for (String name : Arrays.asList("STUDY", "GREETING", "MIXED", "OFF_TOPIC")) counts.put(name, 0L);
            long total = 0;
            for (Object[] row : messages.countLearnerMessagesByCategory()) {
                String name = String.valueOf(row[0]);
                long n = row[1] instanceof Number ? ((Number) row[1]).longValue() : 0L;
                counts.put(name, n);
                total += n;
            }
            long refused = counts.get("OFF_TOPIC") + counts.get("MIXED");
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("counts", counts);
            out.put("total", total);
            out.put("refused", refused);
            out.put("refusedPercent", total == 0 ? 0 : Math.round(refused * 1000.0 / total) / 10.0);
            return out;
        });
    }

    /** category: OFF_TOPIC, MIXED or anything else for both. */
    public List<Map<String, Object>> refusedMessages(String category, int limit) {
        final List<String> categories = REFUSED_CATEGORIES.contains(category)
                ? Collections.singletonList(category) : REFUSED_CATEGORIES;
        final int size = Math.max(1, Math.min(100, limit));
        return read.execute(tx -> {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (ChatMessage m : messages.findLearnerMessagesByCategories(categories, PageRequest.of(0, size))) {
                AppUser owner = m.getConversation().getUser();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", m.getId());
                row.put("conversationId", m.getConversation().getId());
                row.put("userId", owner.getId());
                row.put("name", owner.getName());
                row.put("email", owner.getEmail());
                row.put("category", m.getCategory());
                row.put("message", clip(m.getContent(), 500));
                row.put("createdAt", m.getCreatedAt().toString());
                out.add(row);
            }
            return out;
        });
    }

    // ==== helpers ============================================================================================

    private Map<String, Object> context(AppUser user) {
        Map<String, Object> context = new LinkedHashMap<String, Object>(ai.chatContext(user));
        try {
            LearningPreference pref = read.execute(tx -> preferences.findByUserId(user.getId()).orElse(null));
            if (pref != null) {
                context.put("preferredLanguage", clip(pref.getLanguage(), 30));
                context.put("explanationStyle", clip(pref.getExplanationStyle(), 30));
                context.put("learningGoal", clip(pref.getLearningGoal(), 120));
                context.put("selectedSubject", clip(pref.getSelectedSubject(), 60));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not load learning preferences for the chat context: {}", ex.toString());
        }
        return context;
    }

    static String cleanMessage(String raw) {
        String message = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        if (message.isEmpty()) throw new IllegalArgumentException("Type a question for the coach.");
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("Please keep your message under " + MAX_MESSAGE_CHARS + " characters.");
        }
        return message;
    }

    static String titleFrom(String message) {
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 57).trim() + "...";
    }

    /** Sends one SSE event. Returns false when the browser has gone away. */
    private static boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException gone) {
            return false;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String clip(String value, int limit) {
        String v = value == null ? "" : value;
        return v.length() <= limit ? v : v.substring(0, limit);
    }
}
