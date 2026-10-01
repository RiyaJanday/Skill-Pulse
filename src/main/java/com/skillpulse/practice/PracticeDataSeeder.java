package com.skillpulse.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PracticeDataSeeder implements CommandLineRunner {
    private static final long MIN_QUESTIONS_PER_SUBJECT = 50;
    private static final long LEARNING_PATH_QUESTION_COUNT = 1335;
    private static final String QUESTION_BANK_RESOURCE = "/practice/learning_path_question_bank.json";

    private final PracticeSubjectRepository subjects;
    private final PracticeTopicRepository topics;
    private final PracticeQuestionRepository questions;
    private final QuestionOptionRepository options;
    private final UserPracticeAttemptRepository attempts;

    public PracticeDataSeeder(PracticeSubjectRepository subjects,
                              PracticeTopicRepository topics,
                              PracticeQuestionRepository questions,
                              QuestionOptionRepository options,
                              UserPracticeAttemptRepository attempts) {
        this.subjects = subjects;
        this.topics = topics;
        this.questions = questions;
        this.options = options;
        this.attempts = attempts;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (seedQuestionBankFromResource()) {
            return;
        }

        if (subjects.count() > 0) {
            topUpQuestionBanks();
            return;
        }

        PracticeSubject spring = subject("java-spring-boot", "Java / Spring Boot",
                "Backend fundamentals, REST APIs, validation, JPA, and production-ready Spring Boot patterns.", 1);
        topic(spring, "java-oop", "Java OOP", "Classes, interfaces, inheritance, encapsulation.", 1,
                "Which OOP concept hides internal data and exposes controlled methods?", "Encapsulation",
                "Encapsulation keeps object state private and exposes behavior through methods.",
                "Inheritance", "Polymorphism", "Encapsulation", "Compilation",
                "Which keyword is used when a Java class implements an interface?", "implements",
                "A class uses implements to provide behavior promised by an interface.",
                "extends", "implements", "interface", "package");
        topic(spring, "spring-rest", "Spring REST APIs", "Controllers, request mapping, response codes, DTOs.", 2,
                "Which annotation marks a class as a REST controller in Spring Boot?", "@RestController",
                "@RestController combines @Controller and @ResponseBody for JSON APIs.",
                "@Service", "@Entity", "@RestController", "@Repository",
                "Which annotation maps an HTTP POST request to a method?", "@PostMapping",
                "@PostMapping is the shortcut for handling POST requests.",
                "@GetMapping", "@PostMapping", "@PutValue", "@RequestBody");
        topic(spring, "validation", "Validation", "Request validation, DTO constraints, and safe errors.", 3,
                "Which annotation checks that a String is not blank in Bean Validation?", "@NotBlank",
                "@NotBlank rejects null, empty, and whitespace-only strings.",
                "@NotNull", "@NotBlank", "@Email", "@Size",
                "Where should user input validation usually happen first in a Spring API?", "DTO/request layer",
                "DTO validation catches bad input before service logic mutates data.",
                "Database only", "DTO/request layer", "Frontend only", "Logging layer");
        topic(spring, "jpa", "JPA / Hibernate", "Entities, repositories, relationships, and transactions.", 4,
                "Which annotation marks a Java class as a database-backed JPA model?", "@Entity",
                "@Entity tells JPA that the class maps to a database table.",
                "@Bean", "@Entity", "@TableOnly", "@Mapper",
                "Which Spring Data interface commonly provides CRUD methods for an entity?", "JpaRepository",
                "JpaRepository provides common persistence operations and query derivation.",
                "CrudController", "JpaRepository", "EntityManagerFactoryBean", "DataSource");
        topic(spring, "security", "Auth & Security", "Password hashing, sessions, and protected endpoints.", 5,
                "Why should passwords be stored as hashes instead of plain text?", "To reduce damage if the DB leaks",
                "A strong password hash makes stolen database values harder to reuse.",
                "To make login faster", "To reduce damage if the DB leaks", "To avoid validation", "To remove sessions",
                "Which HTTP status commonly represents an unauthenticated request?", "401",
                "401 Unauthorized means the client must authenticate or has an invalid session.",
                "200", "201", "401", "500");

        PracticeSubject sql = subject("sql-databases", "SQL / Databases",
                "Relational modeling, joins, indexes, transactions, and query reasoning.", 2);
        topic(sql, "select-filter", "SELECT & Filtering", "Reading rows with predicates and ordering.", 1,
                "Which SQL clause filters rows before grouping?", "WHERE",
                "WHERE filters source rows before grouping and aggregation.",
                "ORDER BY", "WHERE", "HAVING", "LIMIT",
                "Which clause sorts the final result set?", "ORDER BY",
                "ORDER BY controls the output ordering of query results.",
                "GROUP BY", "SORT", "ORDER BY", "WHERE");
        topic(sql, "joins", "Joins", "Combining rows from related tables.", 2,
                "Which join keeps all rows from the left table?", "LEFT JOIN",
                "LEFT JOIN returns all left rows and matching right rows, using nulls when missing.",
                "INNER JOIN", "LEFT JOIN", "RIGHT ONLY", "CROSS FILTER",
                "Which join returns only matching rows from both tables?", "INNER JOIN",
                "INNER JOIN keeps rows where the join condition matches on both sides.",
                "LEFT JOIN", "FULL JOIN", "INNER JOIN", "ANTI JOIN");
        topic(sql, "indexes", "Indexes", "Speeding reads and understanding tradeoffs.", 3,
                "What is the main benefit of a database index?", "Faster lookup for matching rows",
                "Indexes help the database find rows without scanning the whole table.",
                "More duplicate rows", "Faster lookup for matching rows", "Automatic encryption", "No need for queries",
                "What is a common downside of too many indexes?", "Slower writes",
                "Indexes must be maintained during insert, update, and delete operations.",
                "Slower writes", "No primary keys", "More syntax errors", "No transactions");
        topic(sql, "transactions", "Transactions", "Atomicity, consistency, isolation, durability.", 4,
                "Which ACID property means all operations succeed or none are applied?", "Atomicity",
                "Atomicity prevents partial updates in a transaction.",
                "Isolation", "Durability", "Atomicity", "Indexing",
                "Which command permanently saves a successful transaction?", "COMMIT",
                "COMMIT makes transaction changes durable.",
                "ROLLBACK", "SAVEPOINT", "COMMIT", "SELECT");
        topic(sql, "schema-design", "Schema Design", "Primary keys, foreign keys, normalization.", 5,
                "What does a foreign key usually represent?", "A relationship to another table",
                "Foreign keys link rows across tables and protect referential integrity.",
                "A password hash", "A relationship to another table", "A cached query", "A frontend route",
                "Why normalize database tables?", "To reduce duplication and improve consistency",
                "Normalization reduces repeated data and update anomalies.",
                "To reduce duplication and improve consistency", "To remove relationships", "To avoid SQL", "To store only JSON");

        PracticeSubject dsa = subject("data-structures-algorithms", "Data Structures & Algorithms",
                "Core problem-solving patterns, complexity, arrays, trees, graphs, and sorting.", 3);
        topic(dsa, "complexity", "Time Complexity", "Big O, growth rates, and tradeoffs.", 1,
                "What is the time complexity of binary search on a sorted array?", "O(log n)",
                "Binary search halves the search space after each comparison.",
                "O(1)", "O(log n)", "O(n)", "O(n^2)",
                "Which complexity grows fastest for large n?", "O(n^2)",
                "Quadratic growth outpaces linear and logarithmic growth.",
                "O(log n)", "O(n)", "O(n^2)", "O(1)");
        topic(dsa, "arrays-strings", "Arrays & Strings", "Iteration, two pointers, frequency maps.", 2,
                "Which pattern often solves sorted array pair-sum problems efficiently?", "Two pointers",
                "Two pointers can move inward based on the current sum.",
                "DFS", "Two pointers", "Heapify", "Backtracking only",
                "Which structure is useful for counting character frequency?", "Hash map",
                "A hash map stores counts by character or token.",
                "Queue", "Hash map", "Stack", "TreeSet only");
        topic(dsa, "stacks-queues", "Stacks & Queues", "LIFO, FIFO, and common use cases.", 3,
                "Which data structure follows LIFO order?", "Stack",
                "Stack removes the most recently added item first.",
                "Queue", "Stack", "Graph", "ArrayList",
                "Which data structure is best for BFS traversal?", "Queue",
                "BFS processes nodes level by level using FIFO order.",
                "Stack", "Queue", "HashSet", "Priority only");
        topic(dsa, "trees", "Trees", "Traversal, BSTs, recursion, and depth.", 4,
                "Which traversal visits left subtree, node, then right subtree?", "Inorder",
                "Inorder traversal of a BST yields sorted values.",
                "Preorder", "Postorder", "Inorder", "Level order",
                "What is the height of a tree related to?", "Longest path from root to a leaf",
                "Tree height measures the deepest root-to-leaf path.",
                "Number of edges in a graph", "Longest path from root to a leaf", "Number of queues", "Hash collisions");
        topic(dsa, "graphs", "Graphs", "BFS, DFS, visited sets, and shortest paths.", 5,
                "Why do graph traversals usually track visited nodes?", "To avoid cycles and repeated work",
                "Visited sets prevent infinite loops and duplicate processing.",
                "To sort nodes", "To avoid cycles and repeated work", "To create indexes", "To encrypt edges",
                "Which algorithm finds shortest paths in an unweighted graph?", "BFS",
                "BFS explores by distance layers in an unweighted graph.",
                "DFS", "BFS", "Merge sort", "Binary search");

        PracticeSubject react = subject("react-frontend", "React / Frontend",
                "Components, state, effects, forms, APIs, and responsive UI behavior.", 4);
        topic(react, "components", "Components & Props", "Reusable UI pieces and data flow.", 1,
                "How do parent components pass data to child components in React?", "Props",
                "Props are read-only inputs passed from parent to child.",
                "Sessions", "Props", "SQL joins", "LocalStorage only",
                "What should a React component usually return?", "UI description",
                "A component returns JSX describing the UI.",
                "A database row", "UI description", "An HTTP port", "A compiled class");
        topic(react, "state", "State", "Interactive values and re-rendering.", 2,
                "Which hook stores component state in a function component?", "useState",
                "useState stores values that cause re-rendering when updated.",
                "useFetch", "useState", "useClass", "useSQL",
                "What happens when React state changes?", "The component re-renders",
                "React re-renders to reflect the updated state.",
                "The database restarts", "The component re-renders", "CSS is deleted", "The backend logs out");
        topic(react, "effects", "Effects", "Fetching data and syncing with external systems.", 3,
                "Which hook is commonly used to fetch data after render?", "useEffect",
                "useEffect runs side effects such as API calls after rendering.",
                "useMemo", "useEffect", "useClass", "useEntity",
                "Why should dependencies be listed in a useEffect array?", "To control when the effect reruns",
                "Dependencies tell React when the effect should execute again.",
                "To create a route", "To control when the effect reruns", "To hash passwords", "To open SQL");
        topic(react, "forms", "Forms", "Inputs, validation, and submit handling.", 4,
                "What does event.preventDefault() usually prevent in form submit handlers?", "Full page reload",
                "It stops the browser's default form submission reload.",
                "Password hashing", "Full page reload", "Database joins", "CSS parsing",
                "Where should final validation happen for important user input?", "Frontend and backend",
                "Frontend helps UX, but backend must enforce rules securely.",
                "Frontend only", "Backend never", "Frontend and backend", "CSS only");
        topic(react, "api-integration", "API Integration", "Fetch, JSON, loading, errors, and auth tokens.", 5,
                "Which fetch option sets the HTTP method?", "method",
                "The method property controls GET, POST, PUT, DELETE, and more.",
                "headersOnly", "method", "modeName", "route",
                "Why handle non-2xx responses from fetch manually?", "fetch only rejects on network errors",
                "fetch resolves for HTTP errors, so code should check response.ok.",
                "fetch only rejects on network errors", "JSON cannot fail", "POST is always safe", "CSS needs it");

        PracticeSubject ml = subject("machine-learning-basics", "Machine Learning Basics",
                "Data preparation, supervised learning, metrics, evaluation, and feature reasoning.", 5);
        topic(ml, "supervised-learning", "Supervised Learning", "Training with labeled examples.", 1,
                "What does supervised learning require?", "Labeled training data",
                "Supervised models learn from examples that include target labels.",
                "No data", "Labeled training data", "Only CSS", "Only SQL indexes",
                "Which task predicts a continuous numeric value?", "Regression",
                "Regression estimates numeric targets like price or score.",
                "Classification", "Regression", "Clustering", "Hashing");
        topic(ml, "classification", "Classification", "Predicting categories and labels.", 2,
                "Which metric measures correct predictions over all predictions?", "Accuracy",
                "Accuracy is correct predictions divided by total predictions.",
                "Latency", "Accuracy", "Normalization", "Epoch size only",
                "What does a confusion matrix compare?", "Actual vs predicted classes",
                "It shows how predicted labels match or miss actual labels.",
                "Actual vs predicted classes", "CSS vs HTML", "Rows vs indexes", "Tokens vs sessions");
        topic(ml, "features", "Feature Engineering", "Turning raw data into useful model inputs.", 3,
                "What is feature engineering?", "Creating useful input variables for a model",
                "Feature engineering transforms raw data into signals a model can learn from.",
                "Deleting labels", "Creating useful input variables for a model", "Opening a socket", "Writing CSS",
                "Why normalize numeric features?", "To put values on comparable scales",
                "Normalization prevents large-scale features from dominating some models.",
                "To put values on comparable scales", "To remove all rows", "To create passwords", "To avoid training");
        topic(ml, "evaluation", "Model Evaluation", "Train/test split, validation, and overfitting.", 4,
                "Why use a test set?", "To estimate performance on unseen data",
                "A held-out test set checks generalization after training.",
                "To train twice as fast", "To estimate performance on unseen data", "To store passwords", "To write APIs",
                "What is overfitting?", "Learning training data too specifically",
                "An overfit model performs well on training data but poorly on new data.",
                "Learning training data too specifically", "Deleting the model", "Using too little RAM", "Normalizing labels");
        topic(ml, "model-lifecycle", "Model Lifecycle", "Training, saving, monitoring, and improving models.", 5,
                "Why monitor a model after deployment?", "Real-world data can drift",
                "Data drift can reduce prediction quality over time.",
                "Real-world data can drift", "HTML changes color", "SQL stops joining", "Java loses classes",
                "What should you do when a model performs poorly on new data?", "Review data, features, and retraining",
                "Model quality improves by diagnosing data, features, labels, and retraining strategy.",
                "Ignore it", "Review data, features, and retraining", "Delete all metrics", "Disable validation");

        topUpQuestionBanks();
    }

    private PracticeSubject subject(String slug, String name, String description, int order) {
        PracticeSubject subject = new PracticeSubject();
        subject.setSlug(slug);
        subject.setName(name);
        subject.setDescription(description);
        subject.setDisplayOrder(order);
        return subjects.save(subject);
    }

    private void topic(PracticeSubject subject, String slug, String name, String description, int order,
                       String prompt1, String answer1, String explanation1,
                       String a1, String b1, String c1, String d1,
                       String prompt2, String answer2, String explanation2,
                       String a2, String b2, String c2, String d2) {
        PracticeTopic topic = new PracticeTopic();
        topic.setSubject(subject);
        topic.setSlug(slug);
        topic.setName(name);
        topic.setDescription(description);
        topic.setDisplayOrder(order);
        topic = topics.save(topic);

        question(topic, PracticeQuestion.Difficulty.EASY, prompt1, explanation1, answer1, a1, b1, c1, d1);
        question(topic, PracticeQuestion.Difficulty.MEDIUM, prompt2, explanation2, answer2, a2, b2, c2, d2);
    }

    private void question(PracticeTopic topic, PracticeQuestion.Difficulty difficulty, String prompt,
                          String explanation, String answer, String a, String b, String c, String d) {
        PracticeQuestion question = new PracticeQuestion();
        question.setTopic(topic);
        question.setDifficulty(difficulty);
        question.setPrompt(prompt);
        question.setExplanation(explanation);
        question = questions.save(question);

        option(question, a, answer, 1);
        option(question, b, answer, 2);
        option(question, c, answer, 3);
        option(question, d, answer, 4);
    }

    private void option(PracticeQuestion question, String text, String answer, int order) {
        QuestionOption option = new QuestionOption();
        option.setQuestion(question);
        option.setOptionText(text);
        option.setCorrectOption(text.equals(answer));
        option.setDisplayOrder(order);
        options.save(option);
    }

    private boolean seedQuestionBankFromResource() {
        InputStream stream = getClass().getResourceAsStream(QUESTION_BANK_RESOURCE);
        if (stream == null) {
            return false;
        }

        try {
            JsonNode bank = new ObjectMapper().readTree(stream);
            if (!bank.isArray() || bank.size() == 0) {
                return false;
            }

            if (questions.count() == LEARNING_PATH_QUESTION_COUNT) {
                return true;
            }

            attempts.deleteAll();
            options.deleteAll();
            questions.deleteAll();
            topics.deleteAll();
            subjects.deleteAll();
            attempts.flush();
            options.flush();
            questions.flush();
            topics.flush();
            subjects.flush();

            int subjectOrder = 1;
            long seededQuestions = 0;
            for (JsonNode subjectNode : bank) {
                String subjectName = cleanText(subjectNode.path("name").asText());
                PracticeSubject subject = new PracticeSubject();
                subject.setName(subjectName);
                subject.setSlug(slugFor(subjectName));
                subject.setDescription(descriptionFor(subjectName));
                subject.setDisplayOrder(subjectOrder++);
                subject = subjects.save(subject);

                int moduleOrder = 1;
                for (JsonNode moduleNode : subjectNode.path("modules")) {
                    String moduleName = cleanText(moduleNode.path("name").asText());
                    PracticeTopic topic = new PracticeTopic();
                    topic.setSubject(subject);
                    topic.setSlug(slugFor(moduleName));
                    topic.setName(moduleName);
                    topic.setDescription("Module " + moduleOrder + " in the " + subjectName + " learning path.");
                    topic.setDisplayOrder(moduleOrder++);
                    topic = topics.save(topic);

                    for (JsonNode questionNode : moduleNode.path("questions")) {
                        PracticeQuestion question = new PracticeQuestion();
                        question.setTopic(topic);
                        question.setDifficulty(PracticeQuestion.Difficulty.valueOf(questionNode.path("difficulty").asText("EASY")));
                        question.setPrompt(cleanText(questionNode.path("prompt").asText()));
                        question.setExplanation(cleanText(questionNode.path("explanation").asText()));
                        question = questions.save(question);
                        seededQuestions++;

                        int order = 1;
                        for (JsonNode optionNode : questionNode.path("options")) {
                            QuestionOption option = new QuestionOption();
                            option.setQuestion(question);
                            option.setOptionText(cleanText(optionNode.path("text").asText()));
                            option.setCorrectOption(optionNode.path("correct").asBoolean(false));
                            option.setDisplayOrder(order++);
                            options.save(option);
                        }
                    }
                }
            }

            if (seededQuestions != LEARNING_PATH_QUESTION_COUNT) {
                throw new IllegalStateException("Expected " + LEARNING_PATH_QUESTION_COUNT
                        + " learning path questions but seeded " + seededQuestions + ".");
            }

            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read PDF question bank resource.", ex);
        }
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\t' || ch >= 32) {
                builder.append(ch);
            }
        }
        return builder.toString().trim();
    }

    private Map<String, PracticeSubject> subjectsByName() {
        Map<String, PracticeSubject> result = new HashMap<String, PracticeSubject>();
        for (PracticeSubject subject : subjects.findAllByOrderByDisplayOrderAsc()) {
            result.put(subject.getName(), subject);
        }
        return result;
    }

    private PracticeSubject ensureSubject(Map<String, PracticeSubject> subjectMap, String name) {
        PracticeSubject existing = subjectMap.get(name);
        if (existing != null) {
            return existing;
        }

        PracticeSubject subject = new PracticeSubject();
        subject.setName(name);
        subject.setSlug(slugFor(name));
        subject.setDescription(descriptionFor(name));
        subject.setDisplayOrder(subjectMap.size() + 1);
        subject = subjects.save(subject);
        subjectMap.put(name, subject);
        return subject;
    }

    private List<PracticeTopic> ensureTopics(Map<String, List<PracticeTopic>> topicMap, PracticeSubject subject) {
        if (topicMap.containsKey(subject.getName())) {
            return topicMap.get(subject.getName());
        }

        List<PracticeTopic> subjectTopics = new ArrayList<PracticeTopic>(
                topics.findBySubjectIdOrderByDisplayOrderAsc(subject.getId()));
        if (subjectTopics.isEmpty()) {
            PracticeTopic topic = new PracticeTopic();
            topic.setSubject(subject);
            topic.setSlug(subject.getSlug() + "-mixed-assessment");
            topic.setName("Mixed Assessment");
            topic.setDescription("Curated MCQs from the imported question bank.");
            topic.setDisplayOrder(1);
            subjectTopics.add(topics.save(topic));
        }
        topicMap.put(subject.getName(), subjectTopics);
        return subjectTopics;
    }

    private String slugFor(String name) {
        return name.toLowerCase()
                .replace("&", "and")
                .replace("/", " ")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String descriptionFor(String name) {
        if ("Java / Spring Boot".equals(name)) return "Backend fundamentals, REST APIs, validation, JPA, and Spring Boot patterns.";
        if ("SQL / Databases".equals(name)) return "Relational modeling, joins, indexes, transactions, and query reasoning.";
        if ("Data Structures & Algorithms".equals(name)) return "Core problem-solving patterns, complexity, arrays, trees, graphs, and sorting.";
        if ("React / Frontend".equals(name)) return "Components, state, effects, forms, APIs, and responsive UI behavior.";
        if ("Machine Learning Basics".equals(name)) return "Data preparation, supervised learning, metrics, evaluation, and feature reasoning.";
        return "Curated assessment questions.";
    }

    private void topUpQuestionBanks() {
        for (PracticeSubject subject : subjects.findAllByOrderByDisplayOrderAsc()) {
            long current = questions.countByTopicSubjectId(subject.getId());
            if (current >= MIN_QUESTIONS_PER_SUBJECT) {
                continue;
            }

            List<PracticeTopic> subjectTopics = topics.findBySubjectIdOrderByDisplayOrderAsc(subject.getId());
            if (subjectTopics.isEmpty()) {
                continue;
            }

            long missing = MIN_QUESTIONS_PER_SUBJECT - current;
            for (int i = 0; i < missing; i++) {
                PracticeTopic topic = subjectTopics.get(i % subjectTopics.size());
                int number = (int) current + i + 1;
                PracticeQuestion.Difficulty difficulty = generatedDifficulty(number);
                generatedQuestion(subject, topic, difficulty, number);
            }
        }
    }

    private PracticeQuestion.Difficulty generatedDifficulty(int number) {
        int position = ((number - 1) % 50) + 1;
        if (position <= 18) {
            return PracticeQuestion.Difficulty.EASY;
        }
        if (position <= 36) {
            return PracticeQuestion.Difficulty.MEDIUM;
        }
        return PracticeQuestion.Difficulty.HARD;
    }

    private void generatedQuestion(PracticeSubject subject, PracticeTopic topic,
                                   PracticeQuestion.Difficulty difficulty, int number) {
        String correct = "Apply the core " + topic.getName() + " concept and verify the outcome.";
        String prompt = generatedPrompt(subject, topic, difficulty, number);
        String explanation = generatedExplanation(subject, topic, difficulty);

        question(topic, difficulty, prompt, explanation, correct,
                correct,
                "Ignore the topic details and choose the fastest-looking answer.",
                "Memorize only the option text without understanding the concept.",
                "Skip feedback because practice attempts do not affect skill health.");
    }

    private String generatedPrompt(PracticeSubject subject, PracticeTopic topic,
                                   PracticeQuestion.Difficulty difficulty, int number) {
        if (PracticeQuestion.Difficulty.HARD.equals(difficulty)) {
            return "When solving a " + topic.getName()
                    + " problem, which approach best supports accurate long-term skill health?";
        }
        if (PracticeQuestion.Difficulty.MEDIUM.equals(difficulty)) {
            return "What should you do after choosing an answer in " + topic.getName() + "?";
        }
        return "Which habit is most useful while learning " + topic.getName() + "?";
    }

    private String generatedExplanation(PracticeSubject subject, PracticeTopic topic,
                                        PracticeQuestion.Difficulty difficulty) {
        if (PracticeQuestion.Difficulty.HARD.equals(difficulty)) {
            return "Harder questions check whether you can apply " + topic.getName()
                    + " inside " + subject.getName()
                    + ", review mistakes, and use feedback to improve future performance.";
        }
        if (PracticeQuestion.Difficulty.MEDIUM.equals(difficulty)) {
            return "Medium practice expects you to connect the answer with the reason behind it, not just remember the option.";
        }
        return "The best practice habit is to apply the main concept and read the explanation so your skill health becomes meaningful.";
    }
}
