package org.jetbrains.research.anticopypaster.ide;

import com.intellij.CommonBundle;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageConstants;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.PrepareFailedException;

import org.jetbrains.research.anticopypaster.AntiCopyPasterBundle;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.models.PredictionModel;
import org.jetbrains.research.anticopypaster.models.TensorflowModel;
import org.jetbrains.research.anticopypaster.models.UserSettingsModel;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;
import org.jetbrains.research.anticopypaster.utils.MetricsGatherer;
import org.jetbrains.research.anticopypaster.metrics.MetricCalculator;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.intellij.refactoring.extractMethod.ExtractMethodHandler.getProcessor;
import static org.jetbrains.research.anticopypaster.utils.PsiUtil.*;

/**
 * Shows a notification about discovered Extract Method refactoring opportunity.
 */
public class RefactoringNotificationTask extends TimerTask {
    private static final Logger LOG = Logger.getInstance(RefactoringNotificationTask.class);
    private static final float predictionThreshold = 0.5f; // certainty threshold for models
    private final ConcurrentLinkedQueue<RefactoringEvent> eventsQueue = new ConcurrentLinkedQueue<>();
    private final NotificationGroup notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("Extract Method suggestion");
    private PredictionModel model;
    private final boolean debugMetrics = true;
    private String logFilePath;
    private Project project;


    public RefactoringNotificationTask(Project project) {
        this.project = project;
        this.logFilePath = project.getBasePath() + "/.idea/anticopypaster-refactoringSuggestionsLog.log";
    }

    private PredictionModel getOrInitModel() {
        PredictionModel model = this.model;
        if (model == null) {
//            model = this.model = new UserSettingsModel(new MetricsGatherer(project), project);
            model = this.model = new TensorflowModel();
//            if(debugMetrics){
//                UserSettingsModel settingsModel = (UserSettingsModel) model;
//                try(FileWriter fr = new FileWriter(logFilePath, true)){
//                    String timestamp =
//                            new SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new Date());
//                    fr.write("\n-----------------------\nInitial Metric Thresholds: " +
//                            timestamp + "\n");
//                } catch(IOException ioe) { ioe.printStackTrace(); }
//                settingsModel.logThresholds(logFilePath);
//            }
        }
        return model;
    }

    private boolean isGlobalVariable(PsiElement psiElement) {
        // check if elem is field of a class
        if (psiElement instanceof PsiField field) {
            // make sure field isnt inside a method
            PsiMember parentMember = PsiTreeUtil.getParentOfType(field, PsiMember.class);
            return parentMember instanceof PsiClass && !isLocalVariable(field);
        }
        return false;
    }

    private boolean isLocalVariable(PsiVariable variable) {
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
        return containingMethod != null;
    }

    @Override
    public void run() {
        while (!eventsQueue.isEmpty()) {
//            final PredictionModel model = getOrInitModel();
            try {
                final RefactoringEvent event = eventsQueue.poll();
                ApplicationManager.getApplication().runReadAction(() -> {
                    event.setReasonToExtract(AntiCopyPasterBundle.message(
                            "extract.method.to.simplify.logic.of.enclosing.method"));

                    List<Clone> results = new DuplicatesInspection().resolve(event.getFile(), event.getDestinationMethod(), event.getText()).results();
                    if (results.size() < ProjectSettingsState.getInstance(project).minimumDuplicateMethods)
                        return;

                    notify(event.getProject(),
                            AntiCopyPasterBundle.message(
                                    "extract.method.refactoring.is.available"),
                            getRunnableToShowSuggestionDialog(event)
                    );

                    HashSet<String> variablesInCodeFragment = new HashSet<>();
                    HashMap<String, Integer> variablesCountsInCodeFragment = new HashMap<>();

//                    if (!FragmentCorrectnessChecker.isCorrect(event.getProject(), event.getFile(),
//                            event.getText(),
//                            variablesInCodeFragment,
//                            variablesCountsInCodeFragment)) {
//                        return;
//                    }

                    FeaturesVector featuresVector = calculateFeatures(event);

                    getOrInitModel();
                    float prediction = this.model.predict(featuresVector);
//                    if(debugMetrics){
//                        UserSettingsModel settingsModel = (UserSettingsModel) model;
//                        try(FileWriter fr = new FileWriter(logFilePath, true)){
//                            String timestamp =
//                                    new SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new Date());
//
//                            fr.write("\n-----------------------\nNEW COPY/PASTE EVENT: "
//                                    + timestamp + "\nPASTED CODE:\n"
//                                    + event.getText());
//
//                            if(prediction > predictionThreshold){
//                                fr.write("\n\nSent Notification: True");
//                            }else{
//                                fr.write("\n\nSent Notification: False");
//                            }
//                            fr.write("\nMETRICS\n");
//                        } catch(IOException ioe) { ioe.printStackTrace(); }
//                        settingsModel.logMetrics(logFilePath);
//                    }
//                    event.setReasonToExtract(AntiCopyPasterBundle.message(
//                            "extract.method.to.simplify.logic.of.enclosing.method")); // dummy

                    if ((event.isForceExtraction() || prediction > predictionThreshold) &&
                            canBeExtracted(event)) {
                        notify(event.getProject(),
                                AntiCopyPasterBundle.message(
                                        "extract.method.refactoring.is.available"),
                                getRunnableToShowSuggestionDialog(event)
                        );
                    }
                });
            } catch (Exception e) {
                LOG.error("[ACP] Can't process an event " + e.getMessage());
            }
        }
    }

    public boolean canBeExtracted(RefactoringEvent event) {
        boolean canBeExtracted;
        int startOffset = getStartOffset(event.getEditor(), event.getFile(), event.getText());
        PsiElement[] elementsInCodeFragment = getElements(event.getProject(), event.getFile(),
                startOffset, startOffset + event.getText().length());
        ExtractMethodProcessor processor = getProcessor(event.getProject(), elementsInCodeFragment,
                event.getFile(), false);
        if (processor == null) return false;
        try {
            canBeExtracted = processor.prepare(null);
            processor.findOccurrences();
        } catch (PrepareFailedException e) {
            LOG.error("[ACP] Failed to check if a code fragment can be extracted.", e.getMessage());
            return false;
        }

        return canBeExtracted;
    }

    private Runnable getRunnableToShowSuggestionDialog(RefactoringEvent event) {
        return () -> {
            String message = event.getReasonToExtract();
            if (message.isEmpty()) {
                message = AntiCopyPasterBundle.message("extract.method.to.simplify.logic.of.enclosing.method");
            }

            int startOffset = getStartOffset(event.getEditor(), event.getFile(), event.getText());
            event.getEditor().getSelectionModel().setSelection(startOffset, startOffset + event.getText().length());

            int result =
                    Messages.showOkCancelDialog(message,
                            AntiCopyPasterBundle.message("anticopypaster.recommendation.dialog.name"),
                            CommonBundle.getOkButtonText(),
                            CommonBundle.getCancelButtonText(),
                            Messages.getInformationIcon());

            //result is equal to 0 if a user accepted the suggestion and clicked on OK button, 1 otherwise
            if (result == MessageConstants.OK) {
                new ExtractionTask(event).run();

                AntiCopyPasterUsageStatistics.getInstance(event.getProject()).extractMethodApplied();
            } else {
                AntiCopyPasterUsageStatistics.getInstance(event.getProject()).extractMethodRejected();
            }
        };
    }

    public void notify(Project project, String content, Runnable callback) {
        final Notification notification = notificationGroup.createNotification(content, NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimple(
                AntiCopyPasterBundle.message("anticopypaster.recommendation.notification.action"),
                callback));
        notification.notify(project);
        AntiCopyPasterUsageStatistics.getInstance(project).notificationShown();
    }

    public void addEvent(RefactoringEvent event) {
        this.eventsQueue.add(event);
    }

    /**
     * Calculates the metrics for the pasted code fragment and a method where the code fragment was pasted into.
     */
    private FeaturesVector calculateFeatures(RefactoringEvent event) {
        PsiFile file = event.getFile();
        PsiMethod methodAfterPasting = event.getDestinationMethod();
        int eventBeginLine = getNumberOfLine(file,
                methodAfterPasting.getTextRange().getStartOffset());
        int eventEndLine = getNumberOfLine(file,
                methodAfterPasting.getTextRange().getEndOffset());
        MetricCalculator metricCalculator =
                new MetricCalculator(methodAfterPasting, event.getText(),
                        eventBeginLine, eventEndLine);

        return metricCalculator.getFeaturesVector();
    }

    public void setProject(Project p) {
        this.project = p;
    }
    public Project getProject() {
        return project;
    }
}
