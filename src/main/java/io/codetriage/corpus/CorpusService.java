package io.codetriage.corpus;

import io.codetriage.config.StorageLayout;
import io.codetriage.domain.AiVariant;
import io.codetriage.domain.Lab;
import io.codetriage.domain.repo.AiVariantRepository;
import io.codetriage.domain.repo.LabRepository;
import io.codetriage.llm.GenerationProfile;
import io.codetriage.llm.GenerationProfiles;
import io.codetriage.llm.LlmClient;
import io.codetriage.triage.JobRegistry;
import io.codetriage.util.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the reference corpus for one lab of one group: the set of solutions a
 * model produces for that exact task, under a spread of prompts.
 *
 * This is the piece that makes the whole approach defensible. Instead of asking
 * a model to guess whether a student cheated, it produces artefacts that can be
 * put side by side with the student's code and shown to them.
 */
@Service
public class CorpusService {

    private static final Logger log = LoggerFactory.getLogger(CorpusService.class);

    /**
     * Structural nudges applied across repeats of each profile.
     *
     * These replace what temperature used to do. Temperature is rejected by
     * current models, and in any case it only jiggled word choice; asking for a
     * different shape of solution produces genuinely different code, which is
     * what the corpus needs to span.
     */
    private static final String[] VARIATIONS = {
            "",
            "\n\nKeep the whole solution in a single class.",
            "\n\nSplit the responsibilities across more than one class.",
            "\n\nPrefer straightforward loops over streams or lambdas.",
            "\n\nUse the shortest reasonable method bodies.",
            "\n\nValidate inputs defensively at the start of each method."
    };

    private final AiVariantRepository variants;
    private final LabRepository labs;
    private final LlmClient llm;
    private final StorageLayout storage;
    private final JobRegistry jobs;

    public CorpusService(AiVariantRepository variants, LabRepository labs, LlmClient llm,
                         StorageLayout storage, JobRegistry jobs) {
        this.variants = variants;
        this.labs = labs;
        this.llm = llm;
        this.storage = storage;
        this.jobs = jobs;
    }

    public static String jobKey(Lab lab) {
        return "corpus-" + lab.getId();
    }

    public long countFor(Lab lab) {
        return variants.countByLab(lab);
    }

    public List<AiVariant> listFor(Lab lab) {
        return variants.findByLabOrderByIdAsc(lab);
    }

    @Transactional
    public void clear(Lab lab) throws IOException {
        variants.deleteByLab(lab);
        FileOps.deleteTree(storage.references(lab));
    }

    @Async("jobExecutor")
    public void generateAsync(Long labId, int repeatsPerProfile) {
        Lab lab = labs.findById(labId).orElse(null);
        if (lab == null) {
            return;
        }
        String key = jobKey(lab);
        List<GenerationProfile> profiles = GenerationProfiles.DEFAULTS;
        int repeats = Math.max(1, Math.min(repeatsPerProfile, VARIATIONS.length));
        int total = profiles.size() * repeats;

        jobs.start(key, "Generating reference solutions for " + lab.getDisplayName(), total);

        if (!llm.isConfigured()) {
            jobs.fail(key, "ANTHROPIC_API_KEY is not set.");
            return;
        }
        if (!lab.hasTaskText()) {
            jobs.fail(key, "This lab has no assignment text yet. Upload the assignment "
                    + "or paste the task, then generate again.");
            return;
        }

        int done = 0;
        int failures = 0;
        String lastError = null;

        for (GenerationProfile profile : profiles) {
            for (int repeat = 0; repeat < repeats; repeat++) {
                done++;
                jobs.progress(key, "Profile " + profile.key() + ", variation " + (repeat + 1), done);
                try {
                    generateOne(lab, profile, VARIATIONS[repeat]);
                } catch (Exception e) {
                    failures++;
                    lastError = e.getMessage();
                    log.warn("Reference generation failed for {} profile {}: {}",
                            lab.getDisplayName(), profile.key(), e.getMessage());
                }
            }
        }

        long produced = variants.countByLab(lab);
        if (produced == 0) {
            jobs.fail(key, "No reference solutions could be generated. "
                    + (lastError == null ? "Check the API key and the task text." : lastError));
        } else if (failures > 0) {
            jobs.finish(key, produced + " reference solutions ready (" + failures
                    + " attempts failed: " + lastError + ").");
        } else {
            jobs.finish(key, produced + " reference solutions ready.");
        }
    }

    // Not @Transactional on purpose: this is called from generateAsync inside the
    // same bean, so Spring's proxy would not apply it anyway. Each repository
    // save carries its own transaction.
    void generateOne(Lab lab, GenerationProfile profile, String variation) throws IOException {
        String userPrompt = profile.renderUser(lab.effectiveTaskText()) + variation;

        // Temperature is requested but usually suppressed before it is sent; the
        // spread comes from the profile and the variation above.
        String reply = llm.complete(profile.system(), userPrompt, 0.7);

        String code = JavaSourceSplitter.extractCode(reply);
        List<SourceFile> files = JavaSourceSplitter.split(code);
        if (files.isEmpty()) {
            throw new IllegalStateException("The reply contained no usable Java code.");
        }

        AiVariant variant = new AiVariant();
        variant.setLab(lab);
        variant.setProfileKey(profile.key());
        variant.setModel(llm.modelName());
        variant.setTemperature(0.0);
        variant.setSystemPrompt(profile.system());
        variant.setUserPrompt(userPrompt);
        variant.setCode(code);
        variant = variants.save(variant);

        Path dir = storage.references(lab).resolve(variant.jplagName());
        FileOps.deleteTree(dir);
        Files.createDirectories(dir);
        for (SourceFile file : files) {
            Files.writeString(dir.resolve(file.fileName()), file.content());
        }
        variant.setSourceDir(dir.toString());
        variants.save(variant);
    }
}
