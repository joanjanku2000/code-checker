package io.codetriage.similarity;

import de.jplag.JPlag;
import de.jplag.JPlagComparison;
import de.jplag.JPlagResult;
import de.jplag.exceptions.ExitException;
import de.jplag.java.JavaLanguage;
import de.jplag.options.JPlagOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The only class that touches the JPlag API.
 *
 * Kept deliberately small and isolated: JPlag's library surface shifts between
 * majors, so if a signature moves, this is the single file to adjust. The two
 * optional calls (minimum token match, HTML report) go through reflection so a
 * signature change degrades to a logged warning instead of breaking the build.
 */
@Component
public class JPlagRunner {

    private static final Logger log = LoggerFactory.getLogger(JPlagRunner.class);

    /**
     * Compares every submission directory under {@code submissionsRoot} against
     * every other one.
     *
     * @param submissionsRoot directory holding one sub-directory per submission
     * @param baseCodeDir     handed-out boilerplate to discount, or null
     * @param minimumTokenMatch shortest token run counted as a match
     * @param reportZip       where to write JPlag's own HTML report, or null
     */
    public List<RawPair> compare(Path submissionsRoot, Path baseCodeDir,
                                 int minimumTokenMatch, Path reportZip) throws ExitException {

        JavaLanguage language = new JavaLanguage();
        Set<File> roots = Set.of(submissionsRoot.toFile());

        JPlagOptions options = new JPlagOptions(language, roots, Set.of());

        if (baseCodeDir != null) {
            options = options.withBaseCodeSubmissionDirectory(baseCodeDir.toFile());
        }
        options = applyMinimumTokenMatch(options, minimumTokenMatch);

        JPlagResult result = JPlag.run(options);

        List<RawPair> pairs = new ArrayList<>();
        for (JPlagComparison comparison : result.getAllComparisons()) {
            pairs.add(new RawPair(
                    comparison.firstSubmission().getName(),
                    comparison.secondSubmission().getName(),
                    comparison.similarity(),
                    comparison.maximalSimilarity()
            ));
        }

        if (reportZip != null) {
            writeReport(result, reportZip);
        }
        return pairs;
    }

    /**
     * JPlagOptions is an immutable record with with* copies. The name of this
     * particular one has moved before, so it is probed rather than called.
     */
    private JPlagOptions applyMinimumTokenMatch(JPlagOptions options, int minimumTokenMatch) {
        String[] candidates = {"withMinimumTokenMatch", "withMinimumTokenLength"};
        for (String name : candidates) {
            for (Class<?> argType : new Class<?>[]{Integer.class, int.class}) {
                try {
                    Method method = options.getClass().getMethod(name, argType);
                    Object updated = method.invoke(options, minimumTokenMatch);
                    if (updated instanceof JPlagOptions jplagOptions) {
                        return jplagOptions;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try the next candidate
                }
            }
        }
        log.warn("Could not set the minimum token match on JPlagOptions; using JPlag's default. "
                + "Check the JPlagOptions API for this JPlag version.");
        return options;
    }

    /**
     * Writes JPlag's own report archive. Opening it in the JPlag report viewer
     * gives the proper side-by-side, highlighted view of two submissions, which
     * is the evidence actually worth showing a student. Optional: a failure here
     * must never lose the run's numbers.
     */
    private void writeReport(JPlagResult result, Path reportZip) {
        try {
            Class<?> factoryClass = Class.forName("de.jplag.reporting.reportobject.ReportObjectFactory");
            Object factory = factoryClass.getConstructor(File.class).newInstance(reportZip.toFile());

            try {
                Method twoArg = factoryClass.getMethod("createAndSaveReport", JPlagResult.class, String.class);
                twoArg.invoke(factory, result, reportZip.toString());
                return;
            } catch (NoSuchMethodException ignored) {
                // fall through to the single-argument form
            }
            Method oneArg = factoryClass.getMethod("createAndSaveReport", JPlagResult.class);
            oneArg.invoke(factory, result);

        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Could not write the JPlag HTML report ({}). The run's own numbers are unaffected.",
                    e.getMessage());
        }
    }
}
