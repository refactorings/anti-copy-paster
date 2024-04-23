package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.startup.ProjectActivity;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.intellij.openapi.project.Project;
import com.intellij.util.progress.ProgressVisibilityManager;
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
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.ide.predHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

import static com.intellij.remoteServer.util.CloudConfigurationUtil.createCredentialAttributes;
import static org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics.TRANSMISSION_INTERVAL;

public class AntiCopyPasterTelemetry implements ProjectActivity {
    private final NotificationGroup notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Extract Method suggestion");
    private static String username;
    private static String password;
    private static final String REMOTE_HOST = "155.246.39.61";
    private static final String DATABASE_NAME = "anticopypaster";
    private static final String USER_STATISTICS_COLLECTION = "AntiCopyPaster_User_Statistics";
    private static final Logger LOGGER = LoggerFactory.getLogger(AntiCopyPasterTelemetry.class);
    private static int lock = 1;

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        setLock(settings.useNameRec);
        if(settings.useNameRec == 0){
            Thread predserver = new Thread(new predHolder());
            predserver.start();
            String pluginId = "org.jetbrains.research.anticopypaster";
            String pluginPath = PluginManagerCore.getPlugin(PluginId.getId(pluginId)).getPluginPath().toString();
            pluginPath = pluginPath.replace("\\", "/");
            File modelpath = new File(pluginPath+"/code2vec/java14m_model/models/java14_model/dictionaries.bin");
            try {
                if(!modelpath.exists()){
                    String finalPluginPath = pluginPath;
                    Task.Backgroundable task = new Task.Backgroundable(project, "Installing Code2Vec Model", false) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            indicator.setIndeterminate(false);
                            indicator.setFraction(0.1);
                            try {
                                indicator.setText2("Opening AWS channel...");
                                URL website = new URL("https://s3.amazonaws.com/code2vec/model/java14m_model.tar.gz");
                                indicator.setFraction(0.2);
                                ReadableByteChannel rbc = Channels.newChannel(website.openStream());
                                indicator.setFraction(0.3);
                                indicator.setText2("Creating output stream...");
                                FileOutputStream gzOutputStream = new FileOutputStream(finalPluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
                                indicator.setFraction(0.35);
                                indicator.setText2("Transferring zipped files (this may take a while)...");
                                gzOutputStream.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                                indicator.setFraction(0.5);
                                indicator.setText2("Transfer completed");
                                File inputFile = new File(finalPluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
                                indicator.setFraction(0.55);
                                File outputFile = new File(finalPluginPath+"/code2vec/java14m_model", inputFile.getName().substring(0, inputFile.getName().lastIndexOf(".")));
                                indicator.setFraction(0.6);
                                indicator.setText2("Creating GZip I/O streams...");
                                GZIPInputStream in = new GZIPInputStream(new FileInputStream(finalPluginPath+"/code2vec/java14m_model/java14m_model.tar.gz"));
                                FileOutputStream out = new FileOutputStream(outputFile);
                                indicator.setFraction(0.7);
                                indicator.setText2("Copying...");
                                IOUtils.copy(in, out);
                                in.close();
                                out.close();
                                gzOutputStream.close();
                                indicator.setFraction(0.8);
                                indicator.setText2("Files unzipped");
                                File inputFileTar = new File(finalPluginPath+"/code2vec/java14m_model/java14m_model.tar");
                                File outputDir = new File(finalPluginPath+"/code2vec/java14m_model");
                                final List<File> untaredFiles = new LinkedList<File>();
                                final InputStream tarStream = new FileInputStream(inputFileTar);
                                indicator.setFraction(0.85);
                                final TarArchiveInputStream unpackInputStream = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", tarStream);
                                TarArchiveEntry entry = null;
                                indicator.setFraction(0.9);
                                indicator.setText2("Untarring files...");
                                while ((entry = (TarArchiveEntry)unpackInputStream.getNextEntry()) != null) {
                                    final File outputFileTar = new File(outputDir, entry.getName());
                                    final OutputStream outputFileStream = new FileOutputStream(outputFileTar);
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = unpackInputStream.read(buffer)) != -1) {
                                        outputFileStream.write(buffer, 0, bytesRead);
                                    }
                                    outputFileStream.close();
                                    untaredFiles.add(outputFileTar);
                                }
                                indicator.setFraction(0.95);
                                indicator.setText2("Files untarred");
                                unpackInputStream.close();
                                tarStream.close();
                                inputFileTar.delete();
                                inputFile.delete();
                            } catch (IOException|ArchiveException e) {
                                throw new RuntimeException(e);
                            }
                            indicator.setFraction(1.0);
                            indicator.setText2("Installation completed!");
                        }
                    };
                    ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, new BackgroundableProcessIndicator(task));
                }
                Thread jpserver = new Thread(new ACPServer());
                jpserver.start();
            } catch (RuntimeException e) {
                final Notification notificationStart = notificationGroup.createNotification("Installation of the model failed.", NotificationType.INFORMATION);
                notificationStart.notify(project);
            }
        }
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

//    private static void downloadModel(String pluginPath, Project project) throws RuntimeException{
//        try {
//            URL website = new URL("https://s3.amazonaws.com/code2vec/model/java14m_model.tar.gz");
//            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
//            FileOutputStream gzOutputStream = new FileOutputStream(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
//            gzOutputStream.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
//            File inputFile = new File(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz");
//            File outputFile = new File(pluginPath+"/code2vec/java14m_model", inputFile.getName().substring(0, inputFile.getName().lastIndexOf(".")));
//            GZIPInputStream in = new GZIPInputStream(new FileInputStream(pluginPath+"/code2vec/java14m_model/java14m_model.tar.gz"));
//            FileOutputStream out = new FileOutputStream(outputFile);
//            IOUtils.copy(in, out);
//            in.close();
//            out.close();
//            gzOutputStream.close();
//            File inputFileTar = new File(pluginPath+"/code2vec/java14m_model/java14m_model.tar");
//            File outputDir = new File(pluginPath+"/code2vec/java14m_model");
//            final List<File> untaredFiles = new LinkedList<File>();
//            final InputStream tarStream = new FileInputStream(inputFileTar);
//            final TarArchiveInputStream unpackInputStream = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", tarStream);
//            TarArchiveEntry entry = null;
//            while ((entry = (TarArchiveEntry)unpackInputStream.getNextEntry()) != null) {
//                final File outputFileTar = new File(outputDir, entry.getName());
//                final OutputStream outputFileStream = new FileOutputStream(outputFileTar);
//                byte[] buffer = new byte[4096];
//                int bytesRead;
//                while ((bytesRead = unpackInputStream.read(buffer)) != -1) {
//                    outputFileStream.write(buffer, 0, bytesRead);
//                }
//                outputFileStream.close();
//                untaredFiles.add(outputFileTar);
//            }
//            unpackInputStream.close();
//            tarStream.close();
//            inputFileTar.delete();
//            inputFile.delete();
//        } catch (IOException|ArchiveException e) {
//            throw new RuntimeException(e);
//        }
//    }
    public static int getLock() {
        return lock;
    }
    public static void setLock(int value) {
        lock = value;
    }
}
