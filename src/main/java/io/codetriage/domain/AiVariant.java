package io.codetriage.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One generated reference solution ("pseudo-AI submission"). The prompt and
 * settings are stored alongside the code so a flagged match can be shown to the
 * student together with exactly how the reference was produced.
 */
@Entity
@Table(name = "ai_variant", indexes = @Index(name = "idx_var_lab", columnList = "lab_id"))
public class AiVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lab_id")
    private Lab lab;

    @Column(nullable = false, length = 60)
    private String profileKey;

    @Column(length = 120)
    private String model;

    private double temperature;

    @Lob
    @Column(length = 20000)
    private String systemPrompt;

    @Lob
    @Column(length = 20000)
    private String userPrompt;

    @Lob
    @Column(length = 200000)
    private String code;

    @Column(length = 500)
    private String sourceDir;

    @Column(nullable = false)
    private Instant generatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Lab getLab() { return lab; }
    public void setLab(Lab lab) { this.lab = lab; }

    public String getProfileKey() { return profileKey; }
    public void setProfileKey(String profileKey) { this.profileKey = profileKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    @Transient
    public String jplagName() {
        return "ref_" + id + "_" + profileKey;
    }
}
