package com.max480.discord.randombots;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nonnull;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ModStructureVerifier extends ListenerAdapter {
    private static Logger logger = LoggerFactory.getLogger(ModStructureVerifier.class);

    private static final String CHANNELS_SAVE_FILE_NAME = "mod_structure_police_save.csv";
    private static final String FREE_CHANNELS_SAVE_FILE_NAME = "mod_structure_police_save_free.csv";
    private static final String MESSAGES_TO_ANSWERS_FILE_NAME = "mod_structure_police_messages_to_answers.csv";

    private static final Map<Long, Long> responseChannels = new HashMap<>(); // watched channel ID > response channel ID
    private static final Map<Long, String> collabAssetPrefixes = new HashMap<>(); // watched channel ID > collab assets prefix
    private static final Map<Long, String> collabMapPrefixes = new HashMap<>(); // watched channel ID > collab maps prefix

    // watched channel ID > response channel ID but for channels allowing to use the bot freely with --verify
    private static final Map<Long, Long> freeResponseChannels = new HashMap<>();

    private static final Map<Long, Long> messagesToEmbeds = new HashMap<>(); // message ID > embed message ID from the bot

    private static JDA jda;
    private static int analyzedZipCount = 0;

    public static void main(String[] args) throws Exception {
        // load the list of channels the bot should be listening to from disk.
        if (new File(CHANNELS_SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(CHANNELS_SAVE_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 4);
                    responseChannels.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                    collabAssetPrefixes.put(Long.parseLong(split[0]), split[2]);
                    collabMapPrefixes.put(Long.parseLong(split[0]), split[3]);
                });
            }
        }

        if (new File(FREE_CHANNELS_SAVE_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(FREE_CHANNELS_SAVE_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 2);
                    freeResponseChannels.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                });
            }
        }

        // load the list of messages-to-embeds associations from disk.
        if (new File(MESSAGES_TO_ANSWERS_FILE_NAME).exists()) {
            try (Stream<String> lines = Files.lines(Paths.get(MESSAGES_TO_ANSWERS_FILE_NAME))) {
                lines.forEach(line -> {
                    String[] split = line.split(";", 2);
                    messagesToEmbeds.put(Long.parseLong(split[0]), Long.parseLong(split[1]));
                });
            }
        }

        // clean up messages-to-embeds links that are older than 6 months (message IDs are "snowflakes" and the creation date can be deduced from them).
        for (Long messageId : new ArrayList<>(messagesToEmbeds.keySet())) {
            ZonedDateTime messageDate = Instant.ofEpochMilli((messageId >> 22) + 1420070400000L).atZone(ZoneId.systemDefault());
            if (messageDate.isBefore(ZonedDateTime.now().minusMonths(6))) {
                logger.warn("Forgetting message {} because its date is {}", messageId, messageDate);
                messagesToEmbeds.remove(messageId);
            }
        }

        // start up the bot.
        jda = JDABuilder.createLight(SecretConstants.MOD_STRUCTURE_VERIFIER_TOKEN, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new ModStructureVerifier())
                .setActivity(Activity.playing("Type --help for setup instructions"))
                .build().awaitReady();

        // clean up channels that do not exist anymore.
        for (Long channelId : new ArrayList<>(responseChannels.keySet())) {
            if (jda.getGuilds().stream().noneMatch(guild -> guild.getTextChannelById(channelId) != null)) {
                logger.warn("Forgetting channel {} because it does not exist", channelId);
                responseChannels.remove(channelId);
                collabAssetPrefixes.remove(channelId);
                collabMapPrefixes.remove(channelId);
            }
        }
        for (Long channelId : new ArrayList<>(freeResponseChannels.keySet())) {
            if (jda.getGuilds().stream().noneMatch(guild -> guild.getTextChannelById(channelId) != null)) {
                logger.warn("Forgetting channel {} because it does not exist", channelId);
                freeResponseChannels.remove(channelId);
            }
        }

        logger.debug("Bot is currently in following guilds: {}", jda.getGuilds());
    }

    // let the owner know when the bot joins or leaves servers
    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just joined a new server: " + event.getGuild().getName()).queue();
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                .sendMessage("I just left a server: " + event.getGuild().getName()).queue();
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        if (event.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            // failsafe: do not react to our own messages.
            return;
        }

        if (!event.getAuthor().isBot() && (event.getMember().hasPermission(Permission.ADMINISTRATOR)
                || event.getMember().hasPermission(Permission.MANAGE_SERVER))) {

            // anyone with admin or manage server permissions can run commands to set up the bot.
            parseAdminCommand(event);
        }

        if (freeResponseChannels.containsKey(event.getChannel().getIdLong()) && event.getMessage().getContentRaw().startsWith("--verify ")) {
            // a --verify command was sent in a channel where people are allowed to send --verify commands, hmm...
            // there should be 3 parts (so 2 parameters including --verify itself), with all 2 being alphanumeric.
            String[] settings = event.getMessage().getContentRaw().split(" ", 4);
            if (settings.length >= 3 && settings[1].matches("[A-Za-z0-9]+") && settings[2].matches("[A-Za-z0-9]+")) {
                scanMapFromMessage(event, settings[1], settings[2], freeResponseChannels.get(event.getChannel().getIdLong()));
            } else {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--verify [assets folder name] [maps folder name]`\n" +
                        "`[assets folder name]` and `[maps folder name]` should be alphanumeric.").queue();
            }
        } else if (responseChannels.containsKey(event.getChannel().getIdLong())) {
            // message was sent in a watched channel...

            final String expectedCollabAssetPrefix = collabAssetPrefixes.get(event.getChannel().getIdLong());
            final String expectedCollabMapPrefix = collabMapPrefixes.get(event.getChannel().getIdLong());

            scanMapFromMessage(event, expectedCollabAssetPrefix, expectedCollabMapPrefix, responseChannels.get(event.getChannel().getIdLong()));
        }
    }

    /**
     * Scans a map contained in a message (no matter if it is scanned automatically, or with the --verify command).
     *
     * @param event                     The message to scan
     * @param expectedCollabAssetPrefix The prefix for assets to check in the structure
     * @param expectedCollabMapPrefix   The prefix for maps to check in the structure
     * @param responseChannelId         The channel where all problems with the map will be sent
     */
    private void scanMapFromMessage(@NotNull GuildMessageReceivedEvent event, String expectedCollabAssetPrefix, String expectedCollabMapPrefix, long responseChannelId) {
        for (Message.Attachment attachment : event.getMessage().getAttachments()) {
            if (attachment.getFileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                // this is a zip attachment! analyze it.

                event.getMessage().addReaction("\uD83E\uDD14").complete(); // :thinking:

                logger.info("{} sent a file named {} in {} that we should analyze!", event.getMember(), attachment.getFileName(), event.getChannel());
                attachment.downloadToFile(new File("/tmp/modstructurepolice_" + System.currentTimeMillis() + ".zip"))
                        .thenAcceptAsync(file -> analyzeZipFile(event, attachment, expectedCollabAssetPrefix, expectedCollabMapPrefix, file, responseChannelId));
            }
        }

        // try recognizing Google Drive links by regex in the message text.
        String messageText = event.getMessage().getContentRaw();
        String googleDriveId = null;
        Matcher googleDriveLinkFormat1 = Pattern.compile(".*https://drive\\.google\\.com/open\\?id=([A-Za-z0-9_-]+).*", Pattern.DOTALL).matcher(messageText);
        if (googleDriveLinkFormat1.matches()) {
            googleDriveId = googleDriveLinkFormat1.group(1);
        }
        Matcher googleDriveLinkFormat2 = Pattern.compile(".*https://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+).*", Pattern.DOTALL).matcher(messageText);
        if (googleDriveLinkFormat2.matches()) {
            googleDriveId = googleDriveLinkFormat2.group(1);
        }

        if (googleDriveId != null) {
            logger.info("{} sent a Google Drive file with id {} in {} that we should analyze!", event.getMember(), googleDriveId, event.getChannel());

            event.getMessage().addReaction("\uD83E\uDD14").complete(); // :thinking:

            try (InputStream is = new URL("https://www.googleapis.com/drive/v3/files/" + googleDriveId + "?key=" + SecretConstants.GOOGLE_DRIVE_API_KEY + "&alt=media").openStream()) {
                // download the file through the Google Drive API and analyze it.
                File target = new File("/tmp/modstructurepolice_" + System.currentTimeMillis() + ".zip");
                FileUtils.copyToFile(is, target);
                analyzeZipFile(event, null, expectedCollabAssetPrefix, expectedCollabMapPrefix, target, responseChannelId);
            } catch (IOException e) {
                // the file could not be downloaded (the file is probably private or non-existent).
                logger.warn("Could not download file id {}", googleDriveId, e);
                event.getMessage().removeReaction("\uD83E\uDD14").queue(); // :thinking:
                event.getMessage().addReaction("\uD83D\uDCA3").queue(); // :bomb:

                // post a message, since this kind of error might be on the user.
                Optional.ofNullable(event.getGuild().getTextChannelById(responseChannelId))
                        .orElse(event.getChannel())
                        .sendMessage(event.getAuthor().getAsMention() + " I couldn't download the file from the Google Drive link you posted in " + event.getChannel().getAsMention() + "." +
                                " Maybe the file is private or it doesn't exist anymore? :thinking:\nMake sure anyone that has the link can download it.").queue();
            }
        }
    }

    /**
     * Scans a zip file.
     *
     * @param event                     The message event that triggered the scan
     * @param attachment                The Discord attachment file, or null if the file comes from Google Drive
     * @param expectedCollabAssetPrefix The expected collab assets prefix
     * @param expectedCollabMapsPrefix  The expected collab maps prefix for that channel
     * @param file                      The file to scan
     * @param responseChannelId         The channel where all problems with the map will be sent
     */
    private void analyzeZipFile(@NotNull GuildMessageReceivedEvent event, Message.Attachment attachment, String expectedCollabAssetPrefix,
                                String expectedCollabMapsPrefix, File file, long responseChannelId) {

        analyzedZipCount++;
        jda.getPresence().setActivity(Activity.playing(
                analyzedZipCount + " zip" + (analyzedZipCount == 1 ? "" : "s") + " analyzed since startup | --help"));

        logger.debug("Collab assets folder = {}, Collab maps folder = {}", expectedCollabAssetPrefix, expectedCollabMapsPrefix);

        try (ZipFile zipFile = new ZipFile(file)) {
            List<String> problemList = new ArrayList<>();
            Set<String> websiteProblemList = new HashSet<>();

            logger.debug("Scanning invalid asset paths...");

            // asset paths being Assets/ (lua cutscenes), Graphics/Atlases/, Graphics/ColorGrading/ and Tutorials/
            // should match: Graphics/Atlases/[anything]/collabname/[anything]/[anything]
            final List<String> fileListing = zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .collect(Collectors.toList());

            parseProblematicPaths(problemList, websiteProblemList, "assets", "You have assets that are at the wrong place, please move them", fileListing.stream()
                    .filter(entry -> entry.startsWith("Assets/") || entry.startsWith("Graphics/ColorGrading/")
                            || entry.startsWith("Graphics/Atlases/") || entry.startsWith("Tutorials/"))
                    .filter(entry -> !entry.matches("^(Assets|Graphics/Atlases|Graphics/ColorGrading|Tutorials)(/.+)?/" + expectedCollabAssetPrefix + "/.+/.+$"))
                    .collect(Collectors.toList()));

            logger.debug("Scanning invalid XML paths...");

            // XMLs are anything that matches Graphics/[anything].xml
            // should match: Graphics/collabnamexmls/[anything]/[anything].xml
            parseProblematicPaths(problemList, websiteProblemList, "xmls", "You have XMLs that are at the wrong place, please move them", fileListing.stream()
                    .filter(entry -> entry.startsWith("Graphics/") && entry.endsWith(".xml"))
                    .filter(entry -> !entry.matches("^Graphics/" + expectedCollabAssetPrefix + "xmls/.+/.+\\.xml$"))
                    .collect(Collectors.toList()));

            logger.debug("Scanning presence of map bins...");

            // there must be exactly 1 file in the Maps folder.
            long mapCount = fileListing.stream()
                    .filter(entry -> entry.startsWith("Maps/") && entry.endsWith(".bin"))
                    .count();

            String mapPath = null;
            if (mapCount == 0) {
                problemList.add("**There is no map in the Maps folder!** No map will appear in-game.");
                websiteProblemList.add("nomap");
            } else if (mapCount >= 2) {
                problemList.add("There are " + mapCount + " maps in this zip. :thinking:");
                websiteProblemList.add("multiplemaps");
            } else {
                // save it for later
                mapPath = fileListing.stream()
                        .filter(entry -> entry.startsWith("Maps/") && entry.endsWith(".bin"))
                        .findFirst().orElse(null);

                // check its path
                if (!mapPath.matches("^Maps/" + expectedCollabMapsPrefix + "/.+/.+\\.bin$")) {
                    parseProblematicPaths(problemList, websiteProblemList, "badmappath",
                            "Your map is not in the right folder", Collections.singletonList(mapPath));
                }
            }

            // Dialog/English.txt is not required to exist, but if it does, it'd better be valid.
            ZipEntry englishTxt = zipFile.getEntry("Dialog/English.txt");
            if (englishTxt != null) {
                logger.debug("Scanning invalid English.txt entries...");

                List<String> badDialogEntries = new ArrayList<>();

                // dialog entries are matched using the same regex as in-game.
                // it should match: [collabname]_[anything]_[anything] or [englishtxtname]_[anything]_[anything]
                Pattern dialogEntry = Pattern.compile("^\\w+=.*");
                Pattern validDialogEntry = Pattern.compile("^(" + expectedCollabAssetPrefix + ")_[^_]+_.*=.*");
                Pattern altValidDialogEntry = Pattern.compile("^(" + expectedCollabMapsPrefix + ")_[^_]+_.*=.*");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(zipFile.getInputStream(englishTxt)))) {
                    String s;
                    while ((s = br.readLine()) != null) {
                        s = s.trim();
                        if (dialogEntry.matcher(s).matches() && !validDialogEntry.matcher(s).matches()
                                && !altValidDialogEntry.matcher(s).matches()) {

                            badDialogEntries.add(s.substring(0, s.indexOf("=")));
                        }
                    }
                }

                parseProblematicPaths(problemList, websiteProblemList, "badenglish", "You have English.txt entries with invalid names, please rename them", badDialogEntries);
            }

            logger.debug("Scanning everest.yaml...");

            String yamlName = null;
            List<String> dependencies = null;

            // everest.yaml should exist
            ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
            if (everestYaml == null) {
                if (fileListing.stream().anyMatch(f -> f.endsWith("/everest.yaml"))) {
                    problemList.add("You have an everest.yaml, but it is in a subfolder. **Hint:** when zipping your mod, don't zip the folder, but the contents of it. " +
                            "That is, go inside your mod folder, select everything, and compress that!");
                    websiteProblemList.add("misplacedyaml");
                } else {
                    problemList.add("You have no everest.yaml, please create one. You can install this tool to help you out: <https://gamebanana.com/tools/6908>");
                    websiteProblemList.add("noyaml");
                }
            } else {
                try (InputStream is = zipFile.getInputStream(everestYaml)) {
                    // save it in temp directory
                    String dir = "/tmp/everest_yaml_" + System.currentTimeMillis();
                    new File(dir).mkdir();
                    FileUtils.copyToFile(is, new File(dir + "/everest.yaml"));

                    // build a request to everest.yaml validator
                    HttpPostMultipart submit = new HttpPostMultipart("https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator", "UTF-8", new HashMap<>());
                    submit.addFilePart("file", new File(dir + "/everest.yaml"));
                    HttpURLConnection result = submit.finish();

                    // delete the temp file
                    new File(dir + "/everest.yaml").delete();
                    new File(dir).delete();

                    // read the response from everest.yaml validator
                    String resultBody = IOUtils.toString(result.getInputStream(), StandardCharsets.UTF_8);
                    if (!resultBody.contains("Your everest.yaml file seems valid!")) {
                        problemList.add("Your everest.yaml seems to have problems, send it to <https://max480-random-stuff.appspot.com/celeste/everest-yaml-validator> for more details");
                        websiteProblemList.add("yamlinvalid");
                    } else {
                        // grab the mod name and dependency names given by the validator so that we don't have to do that ourselves later. h
                        dependencies = Jsoup.parse(resultBody)
                                .select("li b")
                                .eachText();
                        yamlName = Jsoup.parse(resultBody)
                                .select("div > b")
                                .get(1).text();
                    }
                }
            }

            if (mapPath != null && dependencies != null) {
                // if the map exists and has a proper everest.yaml, then we can check if it contains everything that is needed for the map.
                searchForMissingComponents(problemList, websiteProblemList, fileListing, zipFile, mapPath, dependencies);
            }

            if (problemList.isEmpty()) {
                event.getMessage().removeReaction("\uD83E\uDD14").queue(); // :thinking:
                event.getMessage().addReaction("\uD83D\uDC4C").queue(); // :ok_hand:

                if (attachment != null) {
                    // post an embed to the 2-click installer.
                    EmbedBuilder embedBuilder = new EmbedBuilder()
                            .setTitle("Install " + yamlName, "https://0x0ade.ga/twoclick?" + attachment.getUrl())
                            .setDescription("Posted by " + event.getAuthor().getAsMention())
                            .setTimestamp(Instant.now());
                    event.getChannel().sendMessage(embedBuilder.build())
                            .queue(postedMessage -> {
                                // save the message ID and the embed ID.
                                messagesToEmbeds.put(event.getMessageIdLong(), postedMessage.getIdLong());
                                savePostedMessagesMap(event);
                            });
                }
            } else {
                // ping the user with an issues list in the response channel, truncating the message if it is somehow too long.
                String message = event.getAuthor().getAsMention() + " Oops, there are issues with the zip you just posted in " + event.getChannel().getAsMention() + ":\n- "
                        + String.join("\n- ", problemList);
                if (message.length() > 2000) {
                    message = message.substring(0, 1997) + "...";
                }

                // if there is any "website problem", attach a link to the help website.
                MessageBuilder discordMessage = new MessageBuilder(message);
                if (!websiteProblemList.isEmpty()) {
                    String url = "https://max480-random-stuff.appspot.com/celeste/mod-structure-verifier?collabName=" + expectedCollabAssetPrefix
                            + (!expectedCollabAssetPrefix.equals(expectedCollabMapsPrefix) ? "&collabMapName=" + expectedCollabMapsPrefix : "")
                            + "&" + String.join("&", websiteProblemList);
                    discordMessage.setEmbed(new EmbedBuilder()
                            .setTitle("Click here for more help", url)
                            .build());
                }

                Optional.ofNullable(event.getGuild().getTextChannelById(responseChannelId))
                        .orElse(event.getChannel())
                        .sendMessage(discordMessage.build()).queue();

                event.getMessage().removeReaction("\uD83E\uDD14").queue(); // :thinking:
                event.getMessage().addReaction("❌").queue(); // :x:
            }
        } catch (Exception e) {
            logger.warn("Could not read zip file! Ignoring.", e);
            event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("An error occurred while scanning a zip: " + e.toString()).queue();

            event.getMessage().removeReaction("\uD83E\uDD14").queue(); // :thinking:
            event.getMessage().addReaction("\uD83D\uDCA3").queue(); // :bomb:
        }

        // and delete the file when we're done analyzing it.
        file.delete();
    }

    @Override
    public void onGuildMessageDelete(@NotNull GuildMessageDeleteEvent event) {
        if (messagesToEmbeds.containsKey(event.getMessageIdLong())) {
            // if a user deletes their file, make sure to delete the embed that goes with it.
            event.getChannel().deleteMessageById(messagesToEmbeds.get(event.getMessageIdLong())).queue();
            messagesToEmbeds.remove(event.getMessageIdLong());
            savePostedMessagesMap(event);
        }
    }

    private void searchForMissingComponents(List<String> problemList, Set<String> websiteProblemsList, List<String> fileListing, ZipFile zipFile,
                                            String mapPath, List<String> dependencies) throws IOException {

        // first, let's collect what is available to us with vanilla, the map's assets, and the dependencies.
        Set<String> availableDecals = VanillaDatabase.allVanillaDecals.stream().map(a -> a.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> availableStylegrounds = VanillaDatabase.allVanillaStylegrounds.stream().map(a -> a.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> availableEntities;
        Set<String> availableTriggers;
        Set<String> availableEffects;

        // collect vanilla entity info by grabbing the file left by the update checker.
        try (InputStream vanillaEntities = new FileInputStream("modfilesdatabase/ahorn_vanilla.yaml")) {
            Map<String, List<String>> entitiesList = new Yaml().load(vanillaEntities);
            availableEntities = new HashSet<>(entitiesList.get("Entities"));
            availableTriggers = new HashSet<>(entitiesList.get("Triggers"));
            availableEffects = new HashSet<>(entitiesList.get("Effects"));
        }

        // parallax is an effect too!
        availableEffects.add("parallax");

        // grab the decals and stylegrounds that ship with the mod.
        fileListing.stream()
                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/decals/") && file.endsWith(".png"))
                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                .forEach(availableDecals::add);
        fileListing.stream()
                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/bgs/") && file.endsWith(".png"))
                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                .forEach(availableStylegrounds::add);

        // get the mod updater database to check dependencies. (since everest.yaml was checked earlier, all dependencies should be valid)
        logger.debug("Loading mod database for decal & styleground analysis...");
        Map<String, Map<String, Object>> databaseContents;
        try (InputStream databaseFile = new FileInputStream("uploads/everestupdate.yaml")) {
            databaseContents = new Yaml().load(databaseFile);
        }

        for (String dep : dependencies) {
            if (databaseContents.containsKey(dep)) { // to exclude Everest

                String depUrl = (String) databaseContents.get(dep).get(com.max480.everest.updatechecker.Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");
                if (depUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                    // instead of downloading the file, let's grab its contents from the mod files database left by the update checker.
                    File modFilesDatabaseFile = new File("modfilesdatabase/" +
                            databaseContents.get(dep).get("GameBananaType") + "/" +
                            databaseContents.get(dep).get("GameBananaId") + "/" +
                            depUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml");

                    if (modFilesDatabaseFile.isFile()) {
                        logger.debug("Loading decals and stylegrounds from dependency {} (file {})...", dep, modFilesDatabaseFile.getAbsolutePath());
                        List<String> depFileListing;
                        try (InputStream databaseFile = new FileInputStream(modFilesDatabaseFile)) {
                            depFileListing = new Yaml().load(databaseFile);
                        }

                        // get everything looking like a decal or a styleground.
                        depFileListing.stream()
                                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/decals/") && file.endsWith(".png"))
                                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                                .forEach(availableDecals::add);
                        depFileListing.stream()
                                .filter(file -> file.startsWith("Graphics/Atlases/Gameplay/bgs/") && file.endsWith(".png"))
                                .map(file -> file.substring(26, file.length() - 4).toLowerCase(Locale.ROOT))
                                .forEach(availableStylegrounds::add);
                    }

                    // is there a file for Ahorn entities as well?
                    File modFilesDatabaseAhornFile = new File("modfilesdatabase/" +
                            databaseContents.get(dep).get("GameBananaType") + "/" +
                            databaseContents.get(dep).get("GameBananaId") + "/ahorn_" +
                            depUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml");

                    if (modFilesDatabaseAhornFile.isFile()) {
                        // there is! load the entities, triggers and effects from it.
                        logger.debug("Loading entities, triggers and effects from dependency {} (file {})...", dep, modFilesDatabaseAhornFile.getAbsolutePath());
                        try (InputStream databaseFile = new FileInputStream(modFilesDatabaseAhornFile)) {
                            Map<String, List<String>> entitiesList = new Yaml().load(databaseFile);
                            availableEntities.addAll(entitiesList.get("Entities"));
                            availableTriggers.addAll(entitiesList.get("Triggers"));
                            availableEffects.addAll(entitiesList.get("Effects"));
                        }
                    }
                }
            }

            if (dep.equals("StrawberryJam2021")) {
                // download and analyze the SJ2021 helper.
                try (InputStream databaseStrawberryJam = new URL(SecretConstants.STRAWBERRY_JAM_LOCATION).openStream()) {
                    String whereIsStrawberryJam = new Yaml().<Map<String, Map<String, Object>>>load(databaseStrawberryJam)
                            .get("StrawberryJam2021")
                            .get("URL").toString();

                    addStuffFromSJ2021(availableEntities, availableTriggers, availableEffects, whereIsStrawberryJam);
                }
            }

            if (dep.equals("GravityHelper")) {
                // download and analyze Gravity Helper.
                try (InputStream gravityHelperTags = authenticatedGitHubRequest("https://api.github.com/repos/swoolcock/GravityHelper/tags")) {
                    JSONArray tagsObject = new JSONArray(IOUtils.toString(gravityHelperTags, StandardCharsets.UTF_8));
                    addStuffFromGravityHelper(availableEntities, availableTriggers, availableEffects,
                            tagsObject.getJSONObject(0).getString("zipball_url"));
                }
            }
        }

        // extract the map bin.
        File tempBin = new File("/tmp/mod_bin_" + System.currentTimeMillis() + ".bin");
        logger.debug("Extracting map {} at {}...", mapPath, tempBin.getAbsolutePath());
        try (InputStream is = zipFile.getInputStream(zipFile.getEntry(mapPath))) {
            FileUtils.copyToFile(is, tempBin);
        }

        // convert it to XML using BinToXML.exe (since the host is on Linux, we need to use mono for that).
        File tempXml = new File(tempBin.getAbsolutePath().replace(".bin", ".xml"));
        logger.debug("Converting {} to {}...", tempBin.getAbsolutePath(), tempXml.getAbsolutePath());
        Process process = new ProcessBuilder("/usr/bin/mono", "BinToXML.exe", "-input", tempBin.getAbsolutePath()).start();
        String output = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
        logger.debug("BinToXML output: {}", output);
        tempBin.delete();

        if (!output.contains("Successful!")) {
            // conversion failed
            logger.error("Bin to XML failed: {}", output);
            problemList.add("Something wrong happened while trying to analyze your bin :thinking: check that it is not corrupt.");
        } else {
            // let's start listing everything that's wrong!
            Set<String> badDecals = new HashSet<>();
            Set<String> badSGs = new HashSet<>();
            Set<String> badEntities = new HashSet<>();
            Set<String> badTriggers = new HashSet<>();
            Set<String> badEffects = new HashSet<>();

            try {
                // parse the XML and delete it
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document document = db.parse(tempXml);
                tempXml.delete();

                { // check all decals in a case insensitive way
                    NodeList decals = document.getElementsByTagName("decal");
                    for (int i = 0; i < decals.getLength(); i++) {
                        Node decal = decals.item(i);
                        if (decal.getAttributes() != null && decal.getAttributes().getNamedItem("texture") != null) {
                            String decalName = decal.getAttributes().getNamedItem("texture").getNodeValue().replace("\\", "/");
                            if (decalName.endsWith(".png")) decalName = decalName.substring(0, decalName.length() - 4);
                            if (!availableDecals.contains("decals/" + decalName.toLowerCase(Locale.ROOT))) {
                                badDecals.add(decalName);
                            }
                        }
                    }
                }

                { // check all stylegrounds starting with bgs/ (to exclude stuff from the Misc atlas) in a case-insensitive way.
                    NodeList stylegrounds = document.getElementsByTagName("parallax");
                    for (int i = 0; i < stylegrounds.getLength(); i++) {
                        Node styleground = stylegrounds.item(i);
                        if (styleground.getAttributes() != null && styleground.getAttributes().getNamedItem("texture") != null) {
                            String sgName = styleground.getAttributes().getNamedItem("texture").getNodeValue().replace("\\", "/");
                            if (sgName.endsWith(".png")) sgName = sgName.substring(0, sgName.length() - 4);
                            if (sgName.startsWith("bgs/") && !availableStylegrounds.contains(sgName.toLowerCase(Locale.ROOT))) {
                                badSGs.add(sgName);
                            }
                        }
                    }
                }

                // check entities, triggers and effects.
                checkForMissingEntities(availableEntities, "entities", badEntities, document);
                checkForMissingEntities(availableTriggers, "triggers", badTriggers, document);
                checkForMissingEntities(availableEffects, "Foregrounds", badEffects, document);
                checkForMissingEntities(availableEffects, "Backgrounds", badEffects, document);

                // and list out every single problem!
                parseProblematicPaths(problemList, websiteProblemsList, "missingassets", "You use missing decals in your map, use other ones or make sure your dependencies are set up correctly", new ArrayList<>(badDecals));
                parseProblematicPaths(problemList, websiteProblemsList, "missingassets", "You use missing parallax stylegrounds in your map, use other ones or make sure your dependencies are set up correctly", new ArrayList<>(badSGs));
                parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing entities in your map, make sure your dependencies are set up correctly", new ArrayList<>(badEntities));
                parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing triggers in your map, make sure your dependencies are set up correctly", new ArrayList<>(badTriggers));
                parseProblematicPaths(problemList, websiteProblemsList, "missingentities", "You use missing effects in your map, make sure your dependencies are set up correctly", new ArrayList<>(badEffects));
            } catch (ParserConfigurationException | SAXException e) {
                // something went wrong, so just delete the XML and rethrow the exception to trigger an alert.
                tempXml.delete();
                throw new IOException(e);
            }
        }
    }

    private void checkForMissingEntities(Set<String> availableEntities, String tagName, Set<String> badEntities, Document document) {
        // list all the <entities> tags.
        NodeList entities = document.getElementsByTagName(tagName);
        for (int i = 0; i < entities.getLength(); i++) {
            // ... go through its children...
            Node entityBlock = entities.item(i);
            for (int j = 0; j < entityBlock.getChildNodes().getLength(); j++) {
                // ... and check if this is an entity that exists, eventually replacing "." with "/" since BinToXML seems to turn "/" into "."
                String entityName = entityBlock.getChildNodes().item(j).getNodeName();
                if (!availableEntities.contains(entityName) && !availableEntities.contains(entityName.replace(".", "/"))) {
                    badEntities.add(entityName.replace(".", "/"));
                }
            }
        }
    }

    private void addStuffFromSJ2021(Set<String> availableEntities, Set<String> availableTriggers, Set<String> availableEffects,
                                    String whereIsStrawberryJam) throws IOException {

        List<String> ahornEntities = new LinkedList<>();
        List<String> ahornTriggers = new LinkedList<>();
        List<String> ahornEffects = new LinkedList<>();

        // download file (pretending we are Firefox since Discord hates Java and responds 403 to it for some reason)
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod-ahornscan-sj.zip"))) {
            HttpURLConnection connection = (HttpURLConnection) new URL(whereIsStrawberryJam).openConnection();
            connection.setDoInput(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:62.0) Gecko/20100101 Firefox/62.0");
            connection.connect();

            try (InputStream is = connection.getInputStream()) {
                IOUtils.copy(is, os);
            }
        }

        // scan its contents, opening Ahorn plugin files
        try (ZipFile zipFile = new ZipFile(new File("mod-ahornscan-sj.zip"))) {
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (entry.getName().startsWith("Ahorn/") && entry.getName().endsWith(".jl")) {
                    InputStream inputStream = zipFile.getInputStream(entry);
                    extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, entry.getName(), inputStream);
                }
            }

            logger.info("Found {} Ahorn entities, {} triggers, {} effects in SJ2021.",
                    ahornEntities.size(), ahornTriggers.size(), ahornEffects.size());
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Could not analyze Ahorn plugins from SJ2021", e);
            throw new IOException(e);
        }

        // merge the result into available entities.
        availableEntities.addAll(ahornEntities);
        availableTriggers.addAll(ahornTriggers);
        availableEffects.addAll(ahornEffects);

        FileUtils.forceDelete(new File("mod-ahornscan-sj.zip"));
    }

    private void addStuffFromGravityHelper(Set<String> availableEntities, Set<String> availableTriggers, Set<String> availableEffects,
                                           String whereIsGravityHelper) throws IOException {

        List<String> ahornEntities = new LinkedList<>();
        List<String> ahornTriggers = new LinkedList<>();
        List<String> ahornEffects = new LinkedList<>();

        try (InputStream is = authenticatedGitHubRequest(whereIsGravityHelper)) {
            FileUtils.copyToFile(is, new File("mod-ahornscan-gh.zip"));
        }

        // scan its contents, opening Ahorn plugin files
        try (ZipFile zipFile = new ZipFile(new File("mod-ahornscan-gh.zip"))) {
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                String name = entry.getName();
                name = name.substring(name.indexOf("/") + 1);
                if (name.startsWith("Ahorn/") && name.endsWith(".jl")) {
                    InputStream inputStream = zipFile.getInputStream(entry);
                    extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, name, inputStream);
                }
            }

            logger.info("Found {} Ahorn entities, {} triggers, {} effects in Gravity Helper.",
                    ahornEntities.size(), ahornTriggers.size(), ahornEffects.size());
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Could not analyze Ahorn plugins from Gravity Helper", e);
            throw new IOException(e);
        }

        // merge the result into available entities.
        availableEntities.addAll(ahornEntities);
        availableTriggers.addAll(ahornTriggers);
        availableEffects.addAll(ahornEffects);

        FileUtils.forceDelete(new File("mod-ahornscan-gh.zip"));
    }

    private InputStream authenticatedGitHubRequest(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (SecretConstants.GITHUB_USERNAME + ":" + SecretConstants.GITHUB_PERSONAL_ACCESS_TOKEN).getBytes(UTF_8)));
        return connection.getInputStream();
    }

    // litteral copy-paste from update checker code
    private void extractAhornEntities(List<String> ahornEntities, List<String> ahornTriggers, List<String> ahornEffects,
                                      String file, InputStream inputStream) throws IOException {

        Pattern mapdefMatcher = Pattern.compile(".*@mapdef [A-Za-z]+ \"([^\"]+)\".*");
        Pattern pardefMatcher = Pattern.compile(".*Entity\\(\"([^\"]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                String entityID = null;

                Matcher mapdefMatch = mapdefMatcher.matcher(line);
                if (mapdefMatch.matches()) {
                    entityID = mapdefMatch.group(1);
                }
                Matcher pardefMatch = pardefMatcher.matcher(line);
                if (pardefMatch.matches()) {
                    entityID = pardefMatch.group(1);
                }

                if (entityID != null) {
                    if (file.startsWith("Ahorn/effects/")) {
                        ahornEffects.add(entityID);
                    } else if (file.startsWith("Ahorn/entities/")) {
                        ahornEntities.add(entityID);
                    } else if (file.startsWith("Ahorn/triggers/")) {
                        ahornTriggers.add(entityID);
                    }
                }
            }
        }
    }

    private static void parseProblematicPaths(List<String> problemList, Set<String> websiteProblemList,
                                              String websiteProblem, String problemLabel, List<String> paths) {
        logger.debug("{}: {}", problemLabel, paths);

        if (paths.size() != 0) {
            websiteProblemList.add(websiteProblem);
        }

        // just a formatting method: to display "x", "x and y" or "x and 458 others" depending on how many problematic paths there are.
        if (paths.size() == 1) {
            problemList.add(problemLabel + ": `"
                    + paths.get(0).replace("`", "") + "`");
        } else if (paths.size() == 2) {
            problemList.add(problemLabel + ": `"
                    + paths.get(0).replace("`", "")
                    + "` and `" + paths.get(1).replace("`", "") + "`");
        } else if (paths.size() == 3) {
            problemList.add(problemLabel + ": `"
                    + paths.get(0).replace("`", "")
                    + "`, `" + paths.get(1).replace("`", "")
                    + "` and `" + paths.get(2).replace("`", "") + "`");
        } else if (paths.size() == 4) {
            problemList.add(problemLabel + ": `"
                    + paths.get(0).replace("`", "")
                    + "`, `" + paths.get(1).replace("`", "")
                    + "`, `" + paths.get(2).replace("`", "")
                    + "` and `" + paths.get(3).replace("`", "") + "`");
        } else if (paths.size() > 4) {
            problemList.add(problemLabel + ": `" + paths.get(0).replace("`", "")
                    + "`, `" + paths.get(1).replace("`", "")
                    + "`, `" + paths.get(2).replace("`", "")
                    + "` and " + (paths.size() - 3) + " others");
        }
    }

    private static void parseAdminCommand(GuildMessageReceivedEvent event) {
        String msg = event.getMessage().getContentRaw();
        if (msg.startsWith("--")) {
            // for logging purposes
            logger.debug("{} sent something looking like a command: {}", event.getMember(), msg);
        }

        if (msg.equals("--help")) {
            event.getChannel().sendMessage("`--setup [response channel] [collab assets folder name] [collab maps folder name]`\n" +
                    "will tell the bot to analyze all the .zip files in the current channel, and to post issues in [response channel]." +
                    "\n- [collab assets folder name] should identify the collab/contest and be alphanumeric. It will have to be used for asset folders (graphics, tutorials, etc)." +
                    "\n- [collab maps folder name] is the name of the folder the collab/contest map bins will be in." +
                    " It has to be alphanumeric, and can be identical to the assets folder name.\n\n" +
                    "`--setup-free [response channel]`\n" +
                    "allows everyone on the server to use the `--verify` command in the channel it is posted in, and will post issues in [response channel].\n\n" +
                    "`--verify [assets folder name] [maps folder name]`\n" +
                    "will verify the given map (as an attachment, or as a Google Drive link) with the given parameters, with the same checks as collabs. Both names should be alphanumeric.\n\n" +
                    "`--remove-setup`\n" +
                    "will tell the bot to stop analyzing the .zip files in the current channel.\n\n" +
                    "`--rules`\n" +
                    "will print out the checks run by the bot in the current channel.\n\n" +
                    "`--reactions`\n" +
                    "will give a message explaining what the different reactions from the bot mean.\n\n" +
                    "This bot is brought to you by max480 (max480#4596 on <https://discord.gg/celeste>) - checks on map bins " +
                    "use BinToXML by iSkLz (available at <https://github.com/iSkLz/celestial-compass>).").queue();
        } else if (msg.startsWith("--setup ")) {
            // setting up a new channel!
            String[] settings = msg.split(" ");
            boolean valid = false;

            // there should be 4 parts (so 3 parameters including --setup itself).
            if (settings.length == 4) {
                // parameter 1 should be a channel mention.
                Matcher regex = Pattern.compile("^<#!?([0-9]+)>$").matcher(settings[1]);
                if (regex.matches()) {
                    String channelId = regex.group(1);

                    // parameters 2 and 3 should be alphanumeric.
                    if (event.getGuild().getTextChannelById(channelId) != null && settings[2].matches("[A-Za-z0-9]+")
                            && settings[3].matches("[A-Za-z0-9]+")) {

                        // save the new association!
                        valid = true;
                        responseChannels.put(event.getChannel().getIdLong(), Long.parseLong(channelId));
                        collabAssetPrefixes.put(event.getChannel().getIdLong(), settings[2]);
                        collabMapPrefixes.put(event.getChannel().getIdLong(), settings[3]);

                        saveMap(event, ":white_check_mark: The bot will check zips posted in this channel against those rules:\n"
                                + getRules(event.getChannel().getIdLong()) + "\n\nAny issue found will be posted in <#" + channelId + ">.");
                    }
                }
            }

            if (!valid) {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--setup [response channel] [collab assets folder name] [collab maps folder name]`\n" +
                        "[response channel] should be a mention, and folder names should be alphanumeric.").queue();
            }
        } else if (msg.startsWith("--setup-free ")) {
            // setting up a new free use channel!
            String[] settings = msg.split(" ");
            boolean valid = false;

            // there should be 2 parts (so 1 parameter including --setup-free itself).
            if (settings.length == 2) {
                // parameter 1 should be a channel mention.
                Matcher regex = Pattern.compile("^<#!?([0-9]+)>$").matcher(settings[1]);
                if (regex.matches()) {
                    String channelId = regex.group(1);
                    if (event.getGuild().getTextChannelById(channelId) != null) {
                        valid = true;
                        freeResponseChannels.put(event.getChannel().getIdLong(), Long.parseLong(channelId));
                        saveMap(event, ":white_check_mark: Everyone will be able to use the `--verify [assets folder name] [maps folder name]` " +
                                "command in this channel to check the structure of their mod. Any issue found will be posted in <#" + channelId + ">.");
                    }
                }
            }

            if (!valid) {
                // print help if one of the parameters is invalid.
                event.getChannel().sendMessage("Usage: `--setup-free [response channel]`\n" +
                        "[response channel] should be a mention.").queue();
            }
        } else if (msg.equals("--remove-setup")) {
            if (responseChannels.containsKey(event.getChannel().getIdLong()) || freeResponseChannels.containsKey(event.getChannel().getIdLong())) {
                // forget about the channel.
                responseChannels.remove(event.getChannel().getIdLong());
                collabAssetPrefixes.remove(event.getChannel().getIdLong());
                collabMapPrefixes.remove(event.getChannel().getIdLong());
                freeResponseChannels.remove(event.getChannel().getIdLong());
                saveMap(event, ":white_check_mark: The bot will not scan zips posted in this channel anymore.");
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        } else if (msg.equals("--rules")) {
            if (responseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage("Here is what the bot checks in submitted zips: \n" + getRules(event.getChannel().getIdLong())).queue();
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        } else if (msg.equals("--reactions")) {
            if (responseChannels.containsKey(event.getChannel().getIdLong())) {
                event.getChannel().sendMessage(
                        "When analyzing your zips, the bot will add reactions to it:\n" +
                                ":thinking: = your zip is being analyzed, please wait...\n" +
                                ":ok_hand: = your zip passed inspection with no issues!\n" +
                                ":x: = there are issues with your zip, you should get pinged about it in <#" + responseChannels.get(event.getChannel().getIdLong()) + ">.\n" +
                                ":bomb: = the bot exploded! max480 will look into that soon.").queue();
            } else {
                event.getChannel().sendMessage(":x: The bot is not set up to scan zips sent to this channel.").queue();
            }
        }
    }

    private static void saveMap(GuildMessageReceivedEvent event, String successMessage) {
        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(CHANNELS_SAVE_FILE_NAME))) {
                for (Long channelId : responseChannels.keySet()) {
                    writer.write(channelId + ";" + responseChannels.get(channelId) + ";" + collabAssetPrefixes.get(channelId) + ";" + collabMapPrefixes.get(channelId) + "\n");
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(FREE_CHANNELS_SAVE_FILE_NAME))) {
                for (Long channelId : freeResponseChannels.keySet()) {
                    writer.write(channelId + ";" + freeResponseChannels.get(channelId) + "\n");
                }
            }

            event.getChannel().sendMessage(successMessage).queue();
        } catch (IOException e) {
            logger.error("Error while writing file", e);
            event.getChannel().sendMessage(":x: A technical error occurred.").queue();
            event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("Error occurred while saving response channels list: " + event.getGuild().getName()).queue();
        }
    }

    private static void savePostedMessagesMap(Event event) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(MESSAGES_TO_ANSWERS_FILE_NAME))) {
            for (Map.Entry<Long, Long> entry : messagesToEmbeds.entrySet()) {
                writer.write(entry.getKey() + ";" + entry.getValue() + "\n");
            }
        } catch (IOException e) {
            logger.error("Error while writing file", e);
            event.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("An error occurred while saving sent messages list: " + e.toString()).queue();
        }
    }

    private static String getRules(Long channelId) {
        String collabAssetsName = collabAssetPrefixes.get(channelId);
        String collabMapsName = collabMapPrefixes.get(channelId);
        return "- files in `Assets/`, `Graphics/Atlases/`, `Graphics/ColorGrading/` and `Tutorials/` should have this path: `[basePath]/" + collabAssetsName + "/[subfolder]/[anything]`\n" +
                "- XMLs in `Graphics/` should match: `Graphics/" + collabAssetsName + "xmls/[subfolder]/[anything].xml`\n" +
                "- there should be exactly 1 file in the `Maps` folder, and its path should match: `Maps/" + collabMapsName + "/[subfolder]/[anything].bin`\n" +
                "- if there is an `English.txt`, dialog IDs should match: `" + collabAssetsName + "_[anything]_[anything]`" +
                (collabMapsName.equals(collabAssetsName) ? "" : "or `" + collabMapsName + "_[anything]_[anything]`") + "\n" +
                "- `everest.yaml` should exist and should be valid according to the everest.yaml validator\n" +
                "- all decals, stylegrounds, entities, triggers and effects should be vanilla, packaged with the mod, or from one of the everest.yaml dependencies";
    }
}
