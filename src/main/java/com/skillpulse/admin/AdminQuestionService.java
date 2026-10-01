package com.skillpulse.admin;

import com.skillpulse.auth.AuthService;
import com.skillpulse.practice.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AdminQuestionService {
    private final AuthService auth;
    private final PracticeQuestionRepository questions;
    private final PracticeTopicRepository topics;
    private final QuestionOptionRepository options;
    private final UserPracticeAttemptRepository attempts;
    public AdminQuestionService(AuthService auth, PracticeQuestionRepository questions, PracticeTopicRepository topics,
                                QuestionOptionRepository options, UserPracticeAttemptRepository attempts) {
        this.auth=auth; this.questions=questions; this.topics=topics; this.options=options; this.attempts=attempts;
    }
    @Transactional(readOnly=true)
    public List<AdminQuestionDtos.QuestionRow> list(String token) {
        auth.requireAdmin(token);
        Map<Long,Stats> stats=new HashMap<Long,Stats>();
        for(UserPracticeAttempt attempt:attempts.findAll()){
            Long id=attempt.getQuestion().getId(); Stats s=stats.get(id);
            if(s==null){s=new Stats();stats.put(id,s);} s.attempts++;
            if(Boolean.TRUE.equals(attempt.getCorrect()))s.correct++;
            s.seconds+=Math.max(0,attempt.getTimeTakenSeconds());
        }
        List<AdminQuestionDtos.QuestionRow> result=new ArrayList<AdminQuestionDtos.QuestionRow>();
        for(PracticeQuestion q:questions.findAll()){
            Stats s=stats.get(q.getId()); AdminQuestionDtos.QuestionRow row=new AdminQuestionDtos.QuestionRow();
            row.setId(q.getId()); row.setSubjectId(q.getTopic().getSubject().getId()); row.setSubjectName(q.getTopic().getSubject().getName());
            row.setTopicId(q.getTopic().getId()); row.setTopicName(q.getTopic().getName()); row.setDifficulty(q.getDifficulty().name());
            row.setPrompt(q.getPrompt()); row.setExplanation(q.getExplanation()); row.setActive(Boolean.TRUE.equals(q.getActive()));
            row.setAttempts(s==null?0:s.attempts); row.setAccuracy(s==null||s.attempts==0?0:(int)Math.round(s.correct*100.0/s.attempts));
            row.setAverageTimeSeconds(s==null||s.attempts==0?0:(int)Math.round(s.seconds*1.0/s.attempts));
            for(QuestionOption o:options.findByQuestionIdOrderByDisplayOrderAsc(q.getId()))row.getOptions().add(new AdminQuestionDtos.OptionRow(o.getId(),o.getOptionText(),Boolean.TRUE.equals(o.getCorrectOption())));
            result.add(row);
        }
        return result;
    }
    @Transactional public AdminQuestionDtos.QuestionRow create(String token,AdminQuestionDtos.QuestionRequest request){
        auth.requireAdmin(token); Validated v=validate(request,null); PracticeQuestion q=new PracticeQuestion(); apply(q,v); q.setActive(true); questions.save(q); saveOptions(q,v); return findRow(token,q.getId());
    }
    @Transactional public AdminQuestionDtos.QuestionRow update(String token,Long id,AdminQuestionDtos.QuestionRequest request){
        auth.requireAdmin(token); PracticeQuestion q=questions.findById(id).orElseThrow(()->new IllegalArgumentException("Question not found.")); Validated v=validate(request,id); apply(q,v); questions.save(q); saveOptions(q,v); return findRow(token,id);
    }
    @Transactional public Map<String,Object> setActive(String token,Long id,boolean active){
        auth.requireAdmin(token); PracticeQuestion q=questions.findById(id).orElseThrow(()->new IllegalArgumentException("Question not found.")); q.setActive(active); questions.save(q); Map<String,Object> result=new HashMap<String,Object>(); result.put("id",id);result.put("active",active);return result;
    }
    private AdminQuestionDtos.QuestionRow findRow(String token,Long id){for(AdminQuestionDtos.QuestionRow row:list(token))if(row.getId().equals(id))return row;throw new IllegalArgumentException("Question not found.");}
    private void apply(PracticeQuestion q,Validated v){q.setTopic(v.topic);q.setDifficulty(v.difficulty);q.setPrompt(v.prompt);q.setExplanation(v.explanation);}
    private void saveOptions(PracticeQuestion q,Validated v){List<QuestionOption> old=options.findByQuestionIdOrderByDisplayOrderAsc(q.getId());for(int i=0;i<4;i++){QuestionOption o=i<old.size()?old.get(i):new QuestionOption();o.setQuestion(q);o.setDisplayOrder(i+1);o.setOptionText(v.optionTexts.get(i));o.setCorrectOption(i==v.correctIndex);options.save(o);}}
    private Validated validate(AdminQuestionDtos.QuestionRequest r,Long currentId){
        if(r==null||r.getTopicId()==null)throw new IllegalArgumentException("Select a module."); PracticeTopic topic=topics.findById(r.getTopicId()).orElseThrow(()->new IllegalArgumentException("Module not found."));
        String prompt=clean(r.getPrompt()), explanation=clean(r.getExplanation()); if(prompt.length()<8)throw new IllegalArgumentException("Question text must contain at least 8 characters."); if(explanation.length()<8)throw new IllegalArgumentException("Add a useful explanation of at least 8 characters.");
        if(r.getOptions()==null||r.getOptions().size()!=4)throw new IllegalArgumentException("Exactly four options are required."); if(r.getCorrectIndex()==null||r.getCorrectIndex()<0||r.getCorrectIndex()>3)throw new IllegalArgumentException("Choose one correct answer.");
        List<String> texts=new ArrayList<String>();for(String text:r.getOptions()){String c=clean(text);if(c.isEmpty())throw new IllegalArgumentException("Options cannot be empty.");if(texts.contains(c))throw new IllegalArgumentException("Options must be unique.");texts.add(c);}
        for(PracticeQuestion existing:questions.findByTopicIdOrderByIdAsc(topic.getId()))if((currentId==null||!existing.getId().equals(currentId))&&normalize(existing.getPrompt()).equals(normalize(prompt)))throw new IllegalArgumentException("A duplicate question already exists in this module.");
        PracticeQuestion.Difficulty difficulty;try{difficulty=PracticeQuestion.Difficulty.valueOf(clean(r.getDifficulty()).toUpperCase(Locale.ROOT));}catch(Exception ex){throw new IllegalArgumentException("Select a valid difficulty.");}
        return new Validated(topic,difficulty,prompt,explanation,texts,r.getCorrectIndex());
    }
    private String clean(String v){return v==null?"":v.trim();} private String normalize(String v){return clean(v).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    private static class Stats{long attempts,correct,seconds;}
    private static class Validated{final PracticeTopic topic;final PracticeQuestion.Difficulty difficulty;final String prompt,explanation;final List<String> optionTexts;final int correctIndex;Validated(PracticeTopic t,PracticeQuestion.Difficulty d,String p,String e,List<String> o,int c){topic=t;difficulty=d;prompt=p;explanation=e;optionTexts=o;correctIndex=c;}}
}
