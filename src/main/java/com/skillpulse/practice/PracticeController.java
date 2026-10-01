package com.skillpulse.practice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
public class PracticeController {
    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @GetMapping("/api/practice/subjects")
    public List<PracticeDtos.SubjectResponse> subjects() {
        return practiceService.subjects();
    }

    @GetMapping("/api/practice/subjects/{subjectId}/topics")
    public List<PracticeDtos.TopicResponse> topics(@PathVariable("subjectId") Long subjectId,
                                                   @RequestParam(value = "token", required = false) String token) {
        return practiceService.topics(subjectId, token);
    }

    @GetMapping("/api/practice/topics/{topicId}/questions")
    public List<PracticeDtos.QuestionResponse> questions(@PathVariable("topicId") Long topicId) {
        return practiceService.questions(topicId);
    }

    @GetMapping("/api/practice/plans")
    public List<PracticeDtos.PlanResponse> plans(@RequestParam("token") String token) {
        return practiceService.plans(token);
    }

    @GetMapping("/api/practice/subjects/{subjectId}/assessment")
    public PracticeDtos.AssessmentResponse assessment(@PathVariable("subjectId") Long subjectId,
                                                      @RequestParam(value = "limit", required = false) Integer limit,
                                                      @RequestParam(value = "token", required = false) String token) {
        return practiceService.assessment(subjectId, limit, token);
    }

    @GetMapping("/api/practice/topics/{topicId}/assessment")
    public PracticeDtos.AssessmentResponse moduleAssessment(@PathVariable("topicId") Long topicId,
                                                            @RequestParam("token") String token) {
        return practiceService.moduleAssessment(topicId, token);
    }

    @GetMapping("/api/practice/review/mistakes")
    public PracticeDtos.AssessmentResponse mistakeReview(@RequestParam("token") String token,
                                                         @RequestParam(value = "limit", required = false) Integer limit) {
        return practiceService.mistakeReview(token, limit);
    }

    @PostMapping("/api/practice/attempts")
    public PracticeDtos.AttemptResponse submitAttempt(@RequestBody PracticeDtos.SubmitAttemptRequest request) {
        return practiceService.submitAttempt(request);
    }

}
