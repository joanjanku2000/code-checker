package io.codetriage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "codetriage")
public class AppProperties {

    private String dataDir = "./data";
    private Admin admin = new Admin();
    private Intake intake = new Intake();
    private Llm llm = new Llm();
    private Similarity similarity = new Similarity();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }

    public Intake getIntake() { return intake; }
    public void setIntake(Intake intake) { this.intake = intake; }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Similarity getSimilarity() { return similarity; }
    public void setSimilarity(Similarity similarity) { this.similarity = similarity; }

    public static class Admin {
        private String username = "lecturer";
        private String passwordHash = "";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    }

    public static class Intake {
        private int maxEntries = 400;
        private long maxUncompressedBytes = 40L * 1024 * 1024;
        private int maxCompressionRatio = 120;
        private int maxSourceFiles = 120;
        private List<String> allowedExtensions = List.of(".java");

        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }

        public long getMaxUncompressedBytes() { return maxUncompressedBytes; }
        public void setMaxUncompressedBytes(long v) { this.maxUncompressedBytes = v; }

        public int getMaxCompressionRatio() { return maxCompressionRatio; }
        public void setMaxCompressionRatio(int v) { this.maxCompressionRatio = v; }

        public int getMaxSourceFiles() { return maxSourceFiles; }
        public void setMaxSourceFiles(int v) { this.maxSourceFiles = v; }

        public List<String> getAllowedExtensions() { return allowedExtensions; }
        public void setAllowedExtensions(List<String> v) { this.allowedExtensions = v; }
    }

    public static class Llm {
        private String baseUrl = "https://api.anthropic.com";
        private String apiKey = "";
        private String model = "claude-sonnet-5-5";
        private int maxTokens = 8000;
        private int timeoutSeconds = 180;

        /**
         * Newer models reject `temperature` outright:
         *   400 invalid_request_error: `temperature` is deprecated for this model
         * So it is off by default and the corpus gets its spread from prompt
         * variation instead. Turn it on only for a model that still accepts it.
         */
        private boolean sendTemperature = false;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public boolean isSendTemperature() { return sendTemperature; }
        public void setSendTemperature(boolean sendTemperature) { this.sendTemperature = sendTemperature; }

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class Similarity {
        private int minimumTokenMatch = 9;
        private double outlierZThreshold = 3.5;
        private double absoluteReviewThreshold = 0.80;

        public int getMinimumTokenMatch() { return minimumTokenMatch; }
        public void setMinimumTokenMatch(int v) { this.minimumTokenMatch = v; }

        public double getOutlierZThreshold() { return outlierZThreshold; }
        public void setOutlierZThreshold(double v) { this.outlierZThreshold = v; }

        public double getAbsoluteReviewThreshold() { return absoluteReviewThreshold; }
        public void setAbsoluteReviewThreshold(double v) { this.absoluteReviewThreshold = v; }
    }
}
