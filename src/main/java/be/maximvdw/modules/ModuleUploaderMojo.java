package be.maximvdw.modules;

import be.maximvdw.placeholderapi.internal.PlaceholderPack;
import be.maximvdw.placeholderapi.internal.annotations.*;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "update", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ModuleUploaderMojo extends AbstractMojo {

    /**
     * URL of the Modules API
     */
    @Parameter(property = "urlApi", defaultValue = "http://modules.mvdw-software.com/api/v1")
    String urlApi;

    /**
     * Access token of the project or module
     */
    @Parameter(property = "accessToken", required = true)
    String accessToken;

    @Parameter(property = "projectId")
    String projectId;

    @Parameter(property = "projectName")
    String projectName;

    @Parameter(property = "moduleName")
    String moduleName;

    @Parameter(property = "moduleId")
    String moduleId;

    @Parameter(property = "moduleAuthor", required = false)
    String moduleAuthor;

    @Parameter(property = "moduleDescription", required = false)
    String moduleDescription;

    @Parameter(property = "moduleVersion", required = false)
    String moduleVersion;

    @Parameter(property = "permalink")
    String permalink;

    @Parameter(property = "screenshots")
    String[] screenshots;

    @Parameter(property = "videos")
    String[] videos;

    @Parameter(property = "constraints")
    Properties constraints;

    @Parameter(property = "changes", required = false)
    String changes;

    /**
     * Project Artifact.
     */
    @Parameter(defaultValue = "${project.artifact}", readonly = true, required = true)
    private Artifact artifact;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        getLog().info("MVdW-Software Module Uploader");
        getLog().info("Using API: " + urlApi);

        File projectFile = artifact.getFile();
        // Scan file for information
        try {
            JarFile jarFile = new JarFile(projectFile);
            Enumeration<JarEntry> e = jarFile.entries();

            URL[] urls = {new URL("jar:file:" + projectFile.getPath() + "!/")};

            List runtimeClasspathElements = project.getRuntimeClasspathElements();
            URL[] runtimeUrls = new URL[runtimeClasspathElements.size()];
            for (int i = 0; i < runtimeClasspathElements.size(); i++) {
                String element = (String) runtimeClasspathElements.get(i);
                runtimeUrls[i] = new File(element).toURI().toURL();
            }
            URLClassLoader cl = new URLClassLoader(runtimeUrls,
                    Thread.currentThread().getContextClassLoader());
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                if (je.isDirectory() || !je.getName().endsWith(".class")) {
                    continue;
                }

                String className = je.getName().substring(0, je.getName().length() - 6);
                className = className.replace('/', '.');
                Class<?> c = Class.forName(className, true, cl);

                if (PlaceholderPack.class.isAssignableFrom(c)) {
                    Class<? extends PlaceholderPack> componentClass = c.asSubclass(PlaceholderPack.class);
                    // Load the module constraints
                    Annotation[] annotations = componentClass.getAnnotations();
                    for (Annotation annotation : annotations) {
                        if (annotation instanceof ModuleConstraint) {
                            ModuleConstraint constraint = (ModuleConstraint) annotation;
                            constraints.put(constraint.type().name(), constraint.value());
                        } else if (annotation instanceof ModuleConstraints) {
                            ModuleConstraint[] subConstraints = ((ModuleConstraints) annotation).value();
                            for (ModuleConstraint subConstraint : subConstraints) {
                                constraints.put(subConstraint.type().name(), subConstraint.value());
                            }
                        } else if (annotation instanceof ModuleName) {
                            moduleName = ((ModuleName) annotation).value();
                        } else if (annotation instanceof ModuleVersion) {
                            moduleVersion = ((ModuleVersion) annotation).value();
                        } else if (annotation instanceof ModuleDescription) {
                            moduleDescription = ((ModuleDescription) annotation).value();
                        } else if (annotation instanceof ModuleAuthor) {
                            moduleAuthor = ((ModuleAuthor) annotation).value();
                        } else if (annotation instanceof ModulePermalink) {
                            permalink = ((ModulePermalink) annotation).value();
                        } else if (annotation instanceof ModuleScreenshots) {
                            screenshots = ((ModuleScreenshots) annotation).value();
                        } else if (annotation instanceof ModuleVideos) {
                            videos = ((ModuleVideos) annotation).value();
                        } else if (annotation instanceof ModuleVersionChange) {
                            if (((ModuleVersionChange) annotation).version().equalsIgnoreCase(moduleVersion)) {
                                changes = ((ModuleVersionChange) annotation).value();
                            }
                        } else if (annotation instanceof ModuleVersionChanges) {
                            for (ModuleVersionChange change : ((ModuleVersionChanges) annotation).value()) {
                                if (change.version().equalsIgnoreCase(moduleVersion)) {
                                    changes = change.value();
                                }
                            }
                        }
                    }
                    if (moduleName != null) {
                        break;
                    }
                }
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            return;
        }

        if (accessToken.startsWith("file:")) {
            String filePath = accessToken.substring("file:".length());
            File accessTokenFile = new File(filePath);
            if (accessTokenFile.exists()) {
                try (
                        InputStream fis = new FileInputStream(accessTokenFile);
                        InputStreamReader isr = new InputStreamReader(fis, Charset.forName("UTF-8"));
                        BufferedReader br = new BufferedReader(isr);
                ) {
                    accessToken = br.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        if (projectName != null) {
            getLog().info("Getting project id from name ...");
            projectId = getProjectId();
        }

        getLog().info("Getting module id from name ...");
        moduleId = getModuleId();
        if (moduleId == null) {
            getLog().info("Creating a new module!");
            moduleId = createModule();
        }
        getLog().info("Module id: " + moduleId);

        if (uploadFile(moduleId, projectFile)) {
            getLog().info("Module upload success!");
        }
    }

    public String getProjectId() {
        try {
            String url = urlApi + "/project/fromName/" + URLEncoder.encode(projectName, "UTF-8");
            getLog().info("Sending GET request to: " + url);
            Document document = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .get();
            JSONParser parser = new JSONParser();
            JSONObject responseJson = (JSONObject) parser.parse(document.text());
            if (responseJson.containsKey("project")) {
                return (String) ((JSONObject) responseJson.get("project")).get("id");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public String getModuleId() {
        try {
            String url = urlApi + "/module/" + projectId + "/fromName/" + URLEncoder.encode(moduleName, "UTF-8");
            getLog().info("Sending GET request to: " + url);
            Document document = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .get();
            JSONParser parser = new JSONParser();
            JSONObject responseJson = (JSONObject) parser.parse(document.text());
            if (responseJson.containsKey("module")) {
                return (String) ((JSONObject) responseJson.get("module")).get("id");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public String createModule() {
        try {
            String url = urlApi + "/project/" + projectId + "/createModule";
            getLog().info("Sending POST request to: " + url);
            Connection connection = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .data("name", moduleName)
                    .data("author", moduleAuthor)
                    .data("description", moduleDescription)
                    .header("Authorization", accessToken);

            if (permalink != null) {
                connection.data("permalink", permalink);
            }
            if (screenshots != null) {
                for (String screenshot : screenshots) {
                    connection.data("screenshots[]", screenshot);
                }
            }
            if (videos != null) {
                for (String video : videos) {
                    connection.data("videos[]", video);
                }
            }

            Document document = connection.post();
            JSONParser parser = new JSONParser();
            JSONObject responseJson = (JSONObject) parser.parse(document.text());
            if (responseJson.containsKey("module")) {
                return (String) ((JSONObject) responseJson.get("module")).get("id");
            } else {
                if (responseJson.containsKey("error")) {
                    getLog().error("Error: " + responseJson.get("error"));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public boolean uploadFile(String moduleId, File file) {
        try {
            String url = urlApi + "/module/" + moduleId + "/update";
            getLog().info("Sending POST request to: " + url);
            Connection connection = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .data("name", moduleName)
                    .data("author", moduleAuthor)
                    .data("description", moduleDescription)
                    .data("version", moduleVersion)
                    .data("file", file.getName(), new FileInputStream(file))
                    .header("Authorization", accessToken);

            if (changes != null) {
                connection.data("changes", changes);
            }
            if (permalink != null) {
                connection.data("permalink", permalink);
            }
            if (screenshots != null) {
                for (String screenshot : screenshots) {
                    connection.data("screenshots[]", screenshot);
                }
            }
            if (videos != null) {
                for (String video : videos) {
                    connection.data("videos[]", video);
                }
            }
            if (constraints != null) {
                for (Map.Entry<Object, Object> prop : constraints.entrySet()) {
                    String data = URLEncoder.encode(prop.getKey() + "=" + prop.getValue(), "UTF-8");
                    connection.data("constraints[]", data);
                }
            }

            Document document = connection.post();
            JSONParser parser = new JSONParser();
            getLog().info(document.text());
            JSONObject responseJson = (JSONObject) parser.parse(document.text());
            if (responseJson.containsKey("error")) {
                getLog().error("Error: " + responseJson.get("error"));
                return false;
            }
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return false;
    }

}