package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.ide.util.PropertiesComponent;

import com.jcraft.jsch.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import com.intellij.openapi.project.Project;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

public class SaveToMongoDB {

    private static  String username;
    private static  String password;
    private static final String sshHost = "155.246.39.61";
    private static final int sshPort = 22;
    private static final String mongodbHost = "localhost";
    private static final int mongodbPort = 27017;
    private static final int forwardPort = 27017;
    private static final String DATABASE_NAME = "anticopypaster";
    private static final String USER_STATISTICS_COLLECTION = "AntiCopyPaster_User_Statistics";

    public static void saveStatistics(Project project, int notificationCount, int extractMethodAppliedCount, int extractMethodRejectedCount, int copyCount, int pasteCount) {
        //Get password and username from ProjectSettingsState
        //TODO: possibly add extra security to how username and passwords are stored
        getUsernameAndPassword(project);
        try {
            //First establish an SSH connection with the stevens server
            JSch jsch = new JSch();
            Session session = jsch.getSession(username, sshHost, sshPort);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");

            session.connect();
            System.out.println("Connected");

            //Forward remote 27017 port to local 27017
            session.setPortForwardingL(forwardPort, mongodbHost, mongodbPort);
            System.out.println("Port forwarding successful");

            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(Objects.requireNonNull(makeConnectionString())))
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
                System.err.println("MongoDB exception occurred.");
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("Unexpected exception occurred.");
                e.printStackTrace();
            }
            session.disconnect();
            System.out.println("Session done");
        }catch (JSchException e) {
            System.err.println("Failed to establish SSH connection: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static String makeConnectionString() {
        try {
            return "mongodb://" + URLEncoder.encode(username, StandardCharsets.UTF_8) + ":" +
                    URLEncoder.encode(password, StandardCharsets.UTF_8) + "@localhost/?authSource=admin";
        } catch (Exception e) {
            System.err.println("Error encoding username or password.");
            e.printStackTrace();
            // Handle the exception appropriately, such as throwing a custom exception or returning a default value
            return null;
        }
    }

    private static void getUsernameAndPassword(Project project) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        username = settings.statisticsUsername;
        password = settings.statisticsPassword;
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
