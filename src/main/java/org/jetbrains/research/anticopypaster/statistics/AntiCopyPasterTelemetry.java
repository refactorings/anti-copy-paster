package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

import com.intellij.openapi.startup.ProjectActivity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.intellij.openapi.project.Project;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.intellij.remoteServer.util.CloudConfigurationUtil.createCredentialAttributes;
import static org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics.TRANSMISSION_INTERVAL;

public class AntiCopyPasterTelemetry implements ProjectActivity {

    private static String username;
    private static String password;
    private static final String REMOTE_HOST = "155.246.39.61";
    private static final String DATABASE_NAME = "anticopypaster";
    private static final String USER_STATISTICS_COLLECTION = "AntiCopyPaster_User_Statistics";
    private static final Logger LOGGER = LoggerFactory.getLogger(AntiCopyPasterTelemetry.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        if (settings.statisticsUsername != null && !settings.statisticsUsername.isEmpty() && settings.statisticsPasswordIsSet) {
            AntiCopyPasterUsageStatistics.PluginState usageState =
                    AntiCopyPasterUsageStatistics.getInstance(project).getState();
            long now = System.currentTimeMillis();
            if (usageState != null && now - usageState.lastTransmissionTime >= TRANSMISSION_INTERVAL) {
                usageState.saveToMongoDB(project);
                usageState.lastTransmissionTime = now;
            }
        }
        return Unit.INSTANCE;
    }

    public static void saveStatistics(Project project, int notificationCount, int extractMethodAppliedCount, int extractMethodRejectedCount, int copyCount, int pasteCount) {
        //Get password and username from PasswordSafe
        getUsernameAndPassword(project);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(makeConnectionString()))
                .build();

        try (MongoClient mongoClient = MongoClients.create(settings)) {
            MongoDatabase database = mongoClient.getDatabase(DATABASE_NAME);
            MongoCollection<Document> statisticsCollection = database.getCollection(USER_STATISTICS_COLLECTION);

            // Update database. Update the document if it exists, or create a new one for the user.
            String userId = getUserID();
            Document query = new Document("userId", userId);
            Document updatedDocument = new Document("userId", userId)
                    .append("notificationCount", notificationCount)
                    .append("extractMethodAppliedCount", extractMethodAppliedCount)
                    .append("extractMethodRejectedCount", extractMethodRejectedCount)
                    .append("copyCount", copyCount)
                    .append("pasteCount", pasteCount);

            statisticsCollection.updateOne(query, new Document("$set", updatedDocument), new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            LOGGER.error("Couldn't write to statistics database", e);
        }
    }

    private static String makeConnectionString() {
        return "mongodb://" + URLEncoder.encode(username, StandardCharsets.UTF_8) + ":" +
                URLEncoder.encode(password, StandardCharsets.UTF_8) + "@" + REMOTE_HOST + "/?authSource=admin";
    }

    private static void getUsernameAndPassword(Project project) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        CredentialAttributes credentialAttributes = createCredentialAttributes("mongoDBStatistics", settings.statisticsUsername);
        Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes);
        if (credentials != null) {
            username = credentials.getUserName();
            password = credentials.getPasswordAsString();
        }
    }

    private static String getUserID() {
        // Retrieve existing user ID from PropertiesComponent and return it if it exists.
        String userId = PropertiesComponent.getInstance().getValue("UniqueUserID");
        if (userId != null) return userId;

        // Generate a new random user ID if one doesn't already exist.
        userId = UUID.randomUUID().toString();
        PropertiesComponent.getInstance().setValue("UniqueUserID", userId);
        return userId;
    }
}
