package de.tobiasmichael.me.ResultParser;


import de.tobiasmichael.me.GithubComment.Commenter;
import de.tobiasmichael.me.Util.JacocoParser;
import de.tobiasmichael.me.Util.JacocoReport;
import edu.hm.hafner.analysis.FileReaderFactory;
import edu.hm.hafner.analysis.ParsingException;
import edu.hm.hafner.analysis.Report;
import edu.hm.hafner.analysis.parser.FindBugsParser;
import edu.hm.hafner.analysis.parser.checkstyle.CheckStyleParser;
import edu.hm.hafner.analysis.parser.pmd.PmdParser;
import edu.hm.hafner.analysis.parser.violations.JUnitAdapter;
import edu.hm.hafner.analysis.parser.violations.PitAdapter;
import edu.hm.hafner.grading.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Result parser
 *
 * @author Tobias Effner
 */
public class ResultParser {

    private static Logger logger;

    private static String oAuthToken = null;
    private static String gradingConfig = null;
    private static Level logLevel = Level.INFO;


    /**
     * Main method; handles logger, arguments, parsers and grading.
     *
     * @param args input arguments
     */
    public static void main(String[] args) {
        logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

        if (args.length > 0) {
            parseArguments(args);
            logger.setLevel(logLevel);
        }
        if (oAuthToken == null) {
            logger.warning("No Token provided, so we'll skip the comment!");
        }
        if (gradingConfig == null) {
            logger.warning("No Config provided, so going to use default config!");
            gradingConfig = handleGradingConfig("src/main/resources/default.conf");
        }

        try {
            List<Path> junit_pathList = getPaths("target/surefire-reports/");
            if (junit_pathList.size() == 0) logger.warning("No JUnit files found!");
            List<Report> junit_reportList = new ArrayList<>();
            junit_pathList.forEach(path -> junit_reportList.add(new JUnitAdapter().parse(new FileReaderFactory(path))));
            int issueCounter = junit_reportList.stream().mapToInt(Report::getSize).sum();


            List<Report> pit_reportList = new ArrayList<>();
            // check if junit generated an issue
            if (issueCounter == 0) {
                List<Path> pit_pathList = getPaths("target/pit-reports/");
                if (pit_pathList.size() == 0) logger.warning("No PIT files found!");
                pit_pathList.forEach(path -> pit_reportList.add(new PitAdapter().parse(new FileReaderFactory(path))));
            } else {
                if (junit_reportList.size() > 0) {
                    throw new NoPITFileException("Not all JUnit tests passed!", junit_reportList);
                }
            }

            Report pmd_report = null;
            Report checkstyle_report = null;
            Report findbugs_report = null;
            JacocoReport jacoco_report = null;
            try {
                pmd_report = new PmdParser().parse(new FileReaderFactory(Paths.get("target/pmd.xml")));
                checkstyle_report = new CheckStyleParser().parse(new FileReaderFactory(Paths.get("target/checkstyle-result.xml")));
                findbugs_report = new FindBugsParser(FindBugsParser.PriorityProperty.RANK).parse(new FileReaderFactory(Paths.get("target/spotbugsXml.xml")));
                jacoco_report = new JacocoParser().parse(new FileReaderFactory(Paths.get("target/site/jacoco/jacoco.xml")));
            } catch (ParsingException e) {
                logger.severe("One or more XML file(s) not found!");
            }

            String configuration = getGradingConfig();
            AggregatedScore score = new AggregatedScore(configuration);
            if (checkstyle_report != null) {
                Report finalCheckstyle_report = checkstyle_report;
                Report finalPmd_report = pmd_report;
                Report finalFindbugs_report = findbugs_report;
                score.addAnalysisScores(new AnalysisSupplier() {
                    @Override
                    protected List<AnalysisScore> createScores(AnalysisConfiguration configuration) {
                        List<AnalysisScore> analysisScoreList = new ArrayList<>();
                        analysisScoreList.add(createAnalysisScore(configuration, "Checkstyle", "1", finalCheckstyle_report));
                        analysisScoreList.add(createAnalysisScore(configuration, "PMD", "2", finalPmd_report));
                        analysisScoreList.add(createAnalysisScore(configuration, "FindBugs", "3", finalFindbugs_report));
                        return analysisScoreList;
                    }
                });
            }
            if (junit_reportList.size() > 0) {
                score.addTestScores(new TestSupplier() {
                    @Override
                    protected List<TestScore> createScores(TestConfiguration configuration) {
                        List<TestScore> testScoreList = new ArrayList<>();
                        junit_reportList.forEach(junit_report -> {
                            testScoreList.add(createTestScore(configuration, "JUnit", junit_report));
                        });
                        return testScoreList;
                    }
                });
            }
            if (jacoco_report != null) {
                JacocoReport finalJacoco_report = jacoco_report;
                score.addCoverageScores(new CoverageSupplier() {
                    @Override
                    protected List<CoverageScore> createScores(CoverageConfiguration configuration) {
                        List<CoverageScore> coverageScoreList = new ArrayList<>();
                        coverageScoreList.add(createCoverageScore(configuration, "Branch", "1", (int) finalJacoco_report.getBranch()));
                        coverageScoreList.add(createCoverageScore(configuration, "Line", "2", (int) finalJacoco_report.getLine()));
                        return coverageScoreList;
                    }
                });
            }
            if (pit_reportList.size() > 0) {
                score.addPitScores(new PitSupplier() {
                    @Override
                    protected List<PitScore> createScores(PitConfiguration configuration) {
                        PitScore pitScore = new PitScore.PitScoreBuilder()
                                .withConfiguration(configuration)
                                .withDisplayName("PIT")
                                .withTotalMutations(pit_reportList.get(0).getSizeOf("NORMAL"))
                                .withUndetectedMutations(pit_reportList.get(0).getSizeOf("HIGH"))
                                .build();
                        return Collections.singletonList(pitScore);
                    }
                });
            }

            Commenter commenter = new Commenter(score);
            commenter.commentTo();
        } catch (ParsingException | IOException e) {
            logger.severe(e.toString());
        }
    }


    /**
     * Creates CoverageScores for the AggregatedScore.
     *
     * @param configuration CoverageConfiguration from AggregatedScore
     * @param displayName   Name to display for the score
     * @param id            Number of the score
     * @param percentage    Percentage of coverage
     * @return returns CoverageScore
     */
    private static CoverageScore createCoverageScore(CoverageConfiguration configuration, String displayName, String id, int percentage) {
        return new CoverageScore.CoverageScoreBuilder()
                .withConfiguration(configuration)
                .withDisplayName(displayName)
                .withId(id)
                .withCoveredPercentage(percentage)
                .build();
    }

    /**
     * Creates AnalysisScore for the AggregatedScore.
     *
     * @param configuration AnalysisConfiguration from AggregatedScore
     * @param displayName   Name to display for the score
     * @param id            Number of the score
     * @param report        Report to add to the score
     * @return returns AnalysisScore
     */
    private static AnalysisScore createAnalysisScore(AnalysisConfiguration configuration, String displayName, String id, Report report) {
        return new AnalysisScore.AnalysisScoreBuilder()
                .withConfiguration(configuration)
                .withDisplayName(displayName)
                .withId(id)
                .withTotalErrorsSize(report.getSize())
                .withTotalHighSeveritySize(report.getSizeOf("high"))
                .withTotalNormalSeveritySize(report.getSizeOf("normal"))
                .withTotalLowSeveritySize(report.getSizeOf("low"))
                .build();
    }

    /**
     * Creates TestScores for the AggregatedScore.
     *
     * @param configuration TestConfiguration from AggregatedScore
     * @param displayName   Name to display for the score
     * @param report        Report to add to the score
     * @return returns TestScore
     */
    private static TestScore createTestScore(TestConfiguration configuration, String displayName, Report report) {
        return new TestScore.TestScoreBuilder()
                .withConfiguration(configuration)
                .withDisplayName(displayName)
                .withTotalSize(report.getSize())
                .withFailedSize(report.getSizeOf("failed"))
                .withSkippedSize(report.getSizeOf("skipped"))
                .build();
    }

    /**
     * Returns a list of paths that matches the glob pattern.
     *
     * @param location path where to search for files
     * @return list with paths
     * @throws IOException ioexception
     */
    private static List<Path> getPaths(String location) throws IOException {
        String glob = "glob:**/*.xml";
        List<Path> pathList = new ArrayList<>();
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(glob);
        Files.walkFileTree(Paths.get(location), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (pathMatcher.matches(path)) {
                    pathList.add(path);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return pathList;
    }

    /**
     * Handles arguments passed to the programmm.
     *
     * @param args input variables
     */
    private static void parseArguments(String[] args) {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        for (String arg : arguments) {
            switch (arg) {
                case ("-d"):
                    logLevel = Level.ALL;
                    logger.setLevel(logLevel);
                    logger.info("Loglevel set to all!");
                    break;
                case ("-c"):
                    gradingConfig = arguments.get(arguments.indexOf(arg) + 1);
                    break;
                case ("-t"):
                    oAuthToken = arguments.get(arguments.indexOf(arg) + 1);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Checks if input is a path or a json string and returns valid json;
     * Can lead to an exit if the path was not found or the given string
     * is not a valid json.
     *
     * @param input input string from main method
     * @return valid json string
     */
    private static String handleGradingConfig(String input) {
        Pattern pattern = Pattern.compile(".*/.*");
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            try {
                Path path = Paths.get(input);
                StringBuilder contentBuilder = new StringBuilder();

                Stream<String> stream = Files.lines((path), StandardCharsets.UTF_8);
                stream.forEach(contentBuilder::append);
                input = contentBuilder.toString();
                break;
            } catch (IOException e) {
                logger.severe("Config file could not be found!");
                System.exit(1);
            }
        }

        if (isJSONValid(input)) {
            return input;
        } else {
            logger.severe("Config is not a valid JSON!");
            System.exit(1);
        }
        return null;
    }

    /**
     * Checks if the given string is valid json.
     *
     * @param jsonString string to test if it's valid json
     * @return valid json string
     */
    private static boolean isJSONValid(String jsonString) {
        try {
            new JSONObject(jsonString);
        } catch (JSONException ex) {
            try {
                new JSONArray(jsonString);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Getter for oAuthToken.
     *
     * @return oAuthToken
     */
    public static String getOAuthToken() {
        return oAuthToken;
    }

    /**
     * Getter for gradingConfig.
     *
     * @return gradingConfig
     */
    public static String getGradingConfig() {
        return gradingConfig;
    }
}