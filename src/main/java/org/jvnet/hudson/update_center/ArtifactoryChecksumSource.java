package org.jvnet.hudson.update_center;

import com.google.gson.Gson;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArtifactoryChecksumSource {
    private static final String ARTIFACTORY_API_URL = "https://repo.jenkins-ci.org/api/storage/releases/?list&deep=1";

    private static final String ARTIFACTORY_API_USERNAME = System.getenv("ARTIFACTORY_USERNAME");
    private static final String ARTIFACTORY_API_PASSWORD = System.getenv("ARTIFACTORY_PASSWORD");

    private static ArtifactoryChecksumSource instance;

    private boolean initialized = false;

    // the key is the URI within the repo, with leading /
    // example: /args4j/args4j/2.0.21/args4j-2.0.21-javadoc.jar
    private Map<String, GsonFile> files = new HashMap<>();

    private ArtifactoryChecksumSource() {
    }

    private static class GsonFile {
        public String uri;
        public String sha1;
        public String sha2;
    }

    private static class GsonResponse {
        public String uri;
        public Date created;
        public List<GsonFile> files;
    }

    private void initialize() throws IOException {
        if (initialized) {
            throw new IllegalStateException("re-initialized");
        }
        HttpClient client = new HttpClient();
        GetMethod get = new GetMethod(ARTIFACTORY_API_URL);
        get.addRequestHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((ARTIFACTORY_API_USERNAME + ":" + ARTIFACTORY_API_PASSWORD).getBytes()));
        client.executeMethod(get);
        InputStream body = get.getResponseBodyAsStream();
        Gson gson = new Gson();
        GsonResponse json = gson.fromJson(new InputStreamReader(body), GsonResponse.class);
        for (GsonFile file : json.files) {
            String uri = file.uri;
            if (uri.endsWith(".hpi") || uri.endsWith(".war")) { // we only care about HPI (plugin) and WAR (core) files
                this.files.put(uri, file);
            }
        }
    }

    public static ArtifactoryChecksumSource getInstance() {
        if (instance == null) {
            instance = new ArtifactoryChecksumSource();
        }
        return instance;
    }

    public String getSha1(MavenArtifact artifact) throws IOException {
        ensureInitialized();
        try {
            return files.get(getUri(artifact)).sha1;
        } catch (NullPointerException e) {
            System.out.println("No artifact: " + artifact.toString());
            return null;
        }
    }

    public String getSha256(MavenArtifact artifact) throws IOException {
        ensureInitialized();
        try {
            return files.get(getUri(artifact)).sha2;
        } catch (JSONException e) {
            // not all files have sha256
            System.out.println("No SHA-256: " + artifact.toString());
            return null;
        } catch (NullPointerException e) {
            System.out.println("No artifact: " + artifact.toString());
            return null;
        }
    }

    private void ensureInitialized() throws IOException {
        if (!initialized) {
            initialize();
            initialized = true;
        }
    }

    private String getUri(MavenArtifact a) {
        String basename = a.artifact.artifactId + "-" + a.artifact.version;
        String filename;
        if (a.artifact.classifier != null) {
            filename = basename + "-" + a.artifact.classifier + "." + a.artifact.packaging;
        } else {
            filename = basename + "." + a.artifact.packaging;
        }
        String ret = "/" + a.artifact.groupId.replace(".", "/") + "/" + a.artifact.artifactId + "/" + a.version + "/" + filename;
        return ret;
    }
}
