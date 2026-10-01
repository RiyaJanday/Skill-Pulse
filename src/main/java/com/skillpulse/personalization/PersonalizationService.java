package com.skillpulse.personalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillpulse.auth.AppUser; import com.skillpulse.auth.AuthService;
import com.skillpulse.dashboard.DashboardService;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.practice.PracticeSubject;
import com.skillpulse.practice.PracticeSubjectRepository;
import com.skillpulse.adaptive.AdaptiveLearningService;
import com.skillpulse.adaptive.UserTopicMastery;
import com.skillpulse.adaptive.UserReviewSchedule;
import com.skillpulse.practice.UserPracticeAttemptRepository;
import com.skillpulse.practice.PracticeTopicRepository;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager; import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class PersonalizationService {
    private final AuthService auth; private final LearningPreferenceRepository preferences; private final DashboardService dashboard; private final PracticeSubjectRepository subjects; private final PracticeTopicRepository topics; private final UserPracticeAttemptRepository attempts; private final AdaptiveLearningService adaptive; private final PlanProgressService progress; private final MlServiceClient ml; private final ObjectMapper json; private final TransactionTemplate tx; private static final Logger log=LoggerFactory.getLogger(PersonalizationService.class);
    public PersonalizationService(AuthService auth,LearningPreferenceRepository preferences,DashboardService dashboard,PracticeSubjectRepository subjects,PracticeTopicRepository topics,UserPracticeAttemptRepository attempts,AdaptiveLearningService adaptive,PlanProgressService progress,MlServiceClient ml,ObjectMapper json,PlatformTransactionManager txManager){this.tx=new TransactionTemplate(txManager);this.auth=auth;this.preferences=preferences;this.dashboard=dashboard;this.subjects=subjects;this.topics=topics;this.attempts=attempts;this.adaptive=adaptive;this.progress=progress;this.ml=ml;this.json=json;}
    @Transactional public PersonalizationDtos.PreferenceResponse getPreferences(){return new PersonalizationDtos.PreferenceResponse(preference(auth.requireUser(null)));}
    @Transactional public PersonalizationDtos.PreferenceResponse update(PersonalizationDtos.PreferenceRequest r){
        AppUser user=auth.requireUser(null); validate(r); LearningPreference p=preference(user);
        p.setExplanationStyle(clean(r.getExplanationStyle()));p.setLearningGoal(clean(r.getLearningGoal()));p.setDailyMinutes(r.getDailyMinutes());p.setPreferredDifficulty(clean(r.getPreferredDifficulty()));p.setLanguage(clean(r.getLanguage()));p.setSelectedSubject(clean(r.getSelectedSubject()));p.setUpdatedAt(java.time.Instant.now());
        return new PersonalizationDtos.PreferenceResponse(preferences.save(p));
    }
    /**
     * Not @Transactional on purpose: the LLM call can take up to ~60 s and must not hold a database connection.
     * Step 1 (prepare) reads everything inside one short transaction, step 2 calls the ML service with no
     * transaction open, step 3 saves through PlanProgressService's own transaction.
     */
    public PersonalizationDtos.PlanResponse generate(String requestedSubject){
        final PlanInputs in=tx.execute(status->prepare(requestedSubject));
        PersonalizationDtos.PlanResponse result=fromMlService(in.pref,in.subject,in.adherence,in.signals);
        final boolean fromMl=result!=null;
        if(!fromMl)result=fallback(in.pref,in.subject,in.metrics,in.signals,in.adherence);
        try{progress.save(in.user,in.subject,result,in.adherence);}
        catch(RuntimeException ex){
            if(!fromMl)throw ex;
            log.warn("Saving the ML plan failed ({}); storing the rule-based plan instead.",ex.toString());
            result=fallback(in.pref,in.subject,in.metrics,in.signals,in.adherence);
            progress.save(in.user,in.subject,result,in.adherence);
        }
        return result;
    }
    private static final class PlanInputs{AppUser user;LearningPreference pref;String subject;Map<String,Object> metrics;Map<String,Object> signals;double adherence;}
    private PlanInputs prepare(String requestedSubject){
        AppUser user=auth.requireUser(null); LearningPreference p=preferences.findByUserId(user.getId()).orElseGet(()->defaults(user));
        String subject=clean(requestedSubject);if(subject.isEmpty())subject=p.getSelectedSubject();validateSubject(subject);
        adaptive.initializeFromHistory(user,attempts.findByUserIdOrderByAttemptedAtDesc(user.getId()));
        for(PracticeSubject available:subjects.findAllByOrderByDisplayOrderAsc())if(available.getName().equalsIgnoreCase(subject)){adaptive.ensureColdStart(user,topics.findBySubjectIdOrderByDisplayOrderAsc(available.getId()));break;}
        Map<String,Object> metrics=new LinkedHashMap<String,Object>(dashboard.summary(user)); metrics.remove("user"); metrics.remove("achievements");
        Map<String,Object> signals=signals(user,subject);double adherence=progress.adherence(user.getId());
        // Collect the inputs for the plan (lazy-loaded data is only safe to read inside this transaction).
        PlanInputs in=new PlanInputs();in.user=user;in.pref=p;in.subject=subject;in.metrics=metrics;in.signals=signals;in.adherence=adherence;return in;
    }
    private PersonalizationDtos.PlanResponse fromMlService(LearningPreference p,String subject,double adherence,Map<String,Object> signals){
        Map<String,Object> request=new LinkedHashMap<String,Object>();
        request.put("subject",subject);request.put("explanationStyle",p.getExplanationStyle());request.put("learningGoal",p.getLearningGoal());request.put("dailyMinutes",p.getDailyMinutes());request.put("preferredDifficulty",p.getPreferredDifficulty());request.put("language",p.getLanguage());request.put("adherence",adherence);request.put("signals",signals);
        Map<String,Object> raw=ml.generatePlan(request);if(raw==null)return null;
        try{return sanitize(json.convertValue(raw,PersonalizationDtos.PlanResponse.class));}catch(IllegalArgumentException ex){return null;}
    }
    /** Accepts an ML plan only if PlanProgressService.save can store it; returns null (so the rule-based plan is used) otherwise. */
    private PersonalizationDtos.PlanResponse sanitize(PersonalizationDtos.PlanResponse plan){
        if(plan==null||plan.getDays()==null||plan.getDays().size()!=7)return null;
        Set<Integer> seen=new HashSet<Integer>();
        for(PersonalizationDtos.PlanDay d:plan.getDays()){
            if(d==null||d.getDay()<1||d.getDay()>7||!seen.add(d.getDay()))return null;
            if(d.getMinutes()<10||d.getMinutes()>180)return null;
            if(d.getFocus()==null||d.getFocus().trim().isEmpty())return null;
            if(d.getActivities()==null)return null;
            List<String> cleaned=new ArrayList<String>();
            for(String a:d.getActivities())if(a!=null&&!a.trim().isEmpty())cleaned.add(cut(a.trim(),1000));
            if(cleaned.isEmpty())return null;
            d.setActivities(cleaned);
            d.setFocus(cut(d.getFocus().trim(),500));
            d.setObjective(d.getObjective()==null?"":cut(d.getObjective().trim(),1200));
        }
        plan.setSummary(plan.getSummary()==null||plan.getSummary().trim().isEmpty()?"Personalized learning plan":cut(plan.getSummary().trim(),1200));
        plan.setPrimaryWeakness(plan.getPrimaryWeakness()==null?null:cut(plan.getPrimaryWeakness().trim(),500));
        plan.setEngine(plan.getEngine()==null?"ml-service":cut(plan.getEngine(),120));
        if(plan.getStrengths()==null)plan.setStrengths(new ArrayList<String>());
        return plan;
    }
    /** Weakest practised topic from the mastery signals (list is sorted lowest mastery first), or null for a brand-new learner. */
    private String weakestTopic(Map<String,Object> signals){Object rows=signals==null?null:signals.get("weakTopics");if(rows instanceof List){for(Object row:(List<?>)rows){if(!(row instanceof Map))continue;Map<?,?> r=(Map<?,?>)row;Object name=r.get("topic");Object tries=r.get("attempts");if(name!=null&&!name.toString().trim().isEmpty()&&tries instanceof Number&&((Number)tries).intValue()>0)return name.toString();}}return null;}
    private String cut(String v,int max){return v.length()<=max?v:v.substring(0,max);}
    private Map<String,Object> signals(AppUser user,String subject){Map<String,Object> result=new LinkedHashMap<String,Object>();List<Map<String,Object>> weak=new ArrayList<Map<String,Object>>();for(UserTopicMastery m:adaptive.masteryForSubject(user.getId(),subject)){if(weak.size()>=6)break;Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("topic",m.getTopic().getName());row.put("mastery",Math.round(adaptive.effectiveMastery(m)*100)/100.0);row.put("attempts",m.getAttemptCount());int daysLeft=adaptive.daysUntilThreshold(m,.60);if(daysLeft>=0)row.put("daysToForgettingThreshold",daysLeft);weak.add(row);}result.put("weakTopics",weak);List<Map<String,Object>> missed=new ArrayList<Map<String,Object>>();for(UserReviewSchedule due:adaptive.dueForReview(user.getId(),subject)){if(missed.size()>=5)break;Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("topic",due.getQuestion().getTopic().getName());String promptText=due.getQuestion().getPrompt();row.put("theme",promptText==null?"review":promptText.substring(0,Math.min(100,promptText.length())));row.put("overdueDays",Math.max(0,java.time.Duration.between(due.getDueAt(),java.time.Instant.now()).toDays()));missed.add(row);}result.put("dueReviews",missed);return result;}
    private LearningPreference preference(AppUser user){return preferences.findByUserId(user.getId()).orElseGet(()->preferences.save(defaults(user)));}
    private LearningPreference defaults(AppUser user){LearningPreference p=new LearningPreference();p.setUser(user);return p;}
    private void validate(PersonalizationDtos.PreferenceRequest r){String style=clean(r.getExplanationStyle()).toUpperCase();if(!Arrays.asList("EXAMPLE_FIRST","THEORY_FIRST","PRACTICE_FIRST","CONCISE").contains(style))throw new IllegalArgumentException("Unsupported explanation style.");String difficulty=clean(r.getPreferredDifficulty()).toUpperCase();if(!Arrays.asList("EASY","MEDIUM","HARD","ADAPTIVE").contains(difficulty))throw new IllegalArgumentException("Unsupported difficulty preference.");validateSubject(clean(r.getSelectedSubject()));}
    private void validateSubject(String subject){for(PracticeSubject available:subjects.findAllByOrderByDisplayOrderAsc()){if(available.getName().equalsIgnoreCase(subject))return;}throw new IllegalArgumentException("Please select an available subject.");}
    private String clean(String v){return v==null?"":v.trim();}
    @SuppressWarnings("unchecked") private PersonalizationDtos.PlanResponse fallback(LearningPreference p,String subject,Map<String,Object> metrics,Map<String,Object> signals,double adherence){
        PersonalizationDtos.PlanResponse plan=new PersonalizationDtos.PlanResponse();plan.setEngine("rule-based-fallback");plan.setSummary("A focused seven-day "+subject+" plan based on your preferences and current SkillPulse performance.");
        List<String> strengths=new ArrayList<String>();Object raw=metrics.get("skills");if(raw instanceof List){for(Object item:(List<?>)raw){if(item instanceof com.skillpulse.dashboard.SkillSummary){com.skillpulse.dashboard.SkillSummary s=(com.skillpulse.dashboard.SkillSummary)item;if(s.getScore()>=80)strengths.add(s.getName());}}}
        String weakest=weakestTopic(signals);plan.setPrimaryWeakness(weakest!=null?weakest:subject);plan.setStrengths(strengths);int dm=p.getDailyMinutes();int minutes=adherence<0.4?Math.max(10,(int)Math.round(dm*0.7)):adherence>0.8?Math.min(180,(int)Math.round(dm*1.1)):dm;
        String[] stages={"Foundations","Core concepts","Guided examples","Applied practice","Problem solving","Assessment and corrections","Mixed review and retention"};String[] objectives={"Establish the essential vocabulary and baseline concepts.","Connect the main ideas and recognize when to use them.","Learn through worked examples before attempting similar tasks.","Apply concepts independently in a focused practice set.","Combine concepts to solve realistic problems.","Identify misconceptions and correct weak areas.","Check retention and plan the next learning step."};
        for(int i=1;i<=7;i++){PersonalizationDtos.PlanDay d=new PersonalizationDtos.PlanDay();d.setDay(i);boolean weakDay=weakest!=null&&(i==3||i==5||i==6);d.setFocus((weakDay?weakest:subject)+" — "+stages[i-1]);d.setObjective(objectives[i-1]);d.setExplanationStyle(p.getExplanationStyle());d.setDifficulty(p.getPreferredDifficulty());d.setMinutes(minutes);d.setActivities(Arrays.asList("Study the "+stages[i-1].toLowerCase()+" lesson for "+(weakDay?weakest:subject),"Complete day "+i+" practice questions","Record mistakes and one takeaway from day "+i));plan.getDays().add(d);}return plan;
    }
}
