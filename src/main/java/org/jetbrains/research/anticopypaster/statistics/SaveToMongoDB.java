package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.ide.util.PropertiesComponent;
import java.util.UUID;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

public class SaveToMongoDB {

    // TODO: Finish handling connection details.
    private static final String CONNECTION_STRING = "";
    private static final String DATABASE_NAME = "";
    private static final String USER_STATISTICS_COLLECTION = "AntiCopyPaster_User_Statistics";

    public static void saveStatistics(int notificationCount, int extractMethodAppliedCount, int extractMethodRejectedCount, int copyCount, int pasteCount) {

        MongoClientSettings settings = MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(CONNECTION_STRING))
                                .build();
        MongoClient mongoClient = MongoClients.create(settings);
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

        mongoClient.close();
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
