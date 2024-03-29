package ca.usask.vga.layout.magnetic;

import ca.usask.vga.layout.magnetic.io.JGitCloneRepository;
import ca.usask.vga.layout.magnetic.io.JavaReader;
import org.apache.commons.io.FileUtils;
import org.cytoscape.application.swing.CySwingApplication;
import org.cytoscape.model.CyNetworkManager;
import org.cytoscape.task.read.LoadNetworkFileTaskFactory;
import org.cytoscape.util.swing.FileUtil;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.work.*;
import org.cytoscape.work.swing.DialogTaskManager;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.zeroturnaround.zip.ZipUtil;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Used to create a new graph from existing data, such as
 * a JAR file, Java source folder or a GitHub link.
 */
public class SoftwareImport {

    private final DialogTaskManager dtm;
    private final FileUtil fu;
    private final LoadNetworkFileTaskFactory nftf;
    private final JavaReader.CyAccess readerAccess;
    private final CyNetworkManager nm;
    private final CyNetworkViewManager vm;
    private final FileUtil fileUtil;
    private final CySwingApplication swingApp;

    private boolean cancelRepoDownload = false;

    /**
     * Initializes the parameters for the software import functionality.
     */
    public SoftwareImport(DialogTaskManager dtm, FileUtil fu, LoadNetworkFileTaskFactory nftf, JavaReader.CyAccess readerAccess,
                          CyNetworkManager nm, CyNetworkViewManager vm, FileUtil fileUtil, CySwingApplication swingApp) {
        this.dtm = dtm;
        this.fu = fu;
        this.nftf = nftf;
        this.readerAccess = readerAccess;
        this.nm = nm;
        this.vm = vm;
        this.fileUtil = fileUtil;
        this.swingApp = swingApp;
    }

    /**
     * Prompts the user to select a file to import, then imports the file.
     * If the import is successful, the name of the file is returned.
     */
    public void loadFromFile(Consumer<String> onSuccess) {
        File f = fu.getFile(swingApp.getJFrame(), "New graph from file", FileUtil.LOAD, new HashSet<>());

        if (f == null) return;

        dtm.execute(nftf.createTaskIterator(f), new TaskObserver() {
            public void taskFinished(ObservableTask task) {}
            public void allFinished(FinishStatus finishStatus) {
                if (onSuccess != null && finishStatus.getType() == FinishStatus.Type.SUCCEEDED)
                    onSuccess.accept(f.getName());
            }
        });

    }

    /**
     * Uses the path to a folder containing Java source code to import the source code.
     * If the import is successful, the path of the folder is returned via onSuccess.
     * @param path The path to the folder containing the local files of the source code.
     * @param originalSource The path to the original source code, may be a GitHub link.
     * @param onSuccess The function to call when the import is successful.
     */
    public void loadFromSrcFolder(String path, String originalSource, Consumer<String> onSuccess) {
        System.out.println("Importing Java source code from: " + path);
        if (path.equals("")) return;
        dtm.execute(new TaskIterator(new JavaReader.ReaderTask(path, readerAccess, rt -> {
            if (originalSource.startsWith("http")) {
                String remotePath = originalSource;
                if (!remotePath.endsWith("/")) remotePath += "/";
                if (rt.inMainJavaFolder()) remotePath += "main/java/";
                rt.setRemoteURL(remotePath);
            } else {
                rt.setRemoteURL(""); // No remote URL
            }
            rt.loadIntoView(nm, vm);
            onSuccess.accept(path);
        })));
    }

    /**
     * Uses the path to a folder containing Java source code to import the source code.
     * If the import is successful, the path of the folder is returned via onSuccess.
     * @param path The path to the folder containing the local files of the source code.
     * @param onSuccess The function to call when the import is successful.
     */
    public void loadFromSrcFolder(String path, Consumer<String> onSuccess) {
        loadFromSrcFolder(path, path, onSuccess);
    }

    /**
     * Uses the path to a generic project folder containing Java code to import the source code.
     * If the import is successful, the path of the folder is returned via onSuccess.
     * @param path The path to the folder containing the local files of the source code.
     * @param onSuccess The function to call when the import is successful.
     */
    public void loadFromGenericFolder(String path, String originURL, Consumer<String> onSuccess) {
        String sourceFolder = path + "/src/";
        if (Files.exists(Paths.get(sourceFolder))) {
            loadFromSrcFolder(sourceFolder, originURL, onSuccess);
        } else {
            String subpath = chooseSrcFolderDialogue(path);

            // Make sure any selected sub folders are included in the origin URL.
            String difference = subpath.substring(path.length()).replace("\\", "/");
            System.out.println("Subpath: " + difference);

            loadFromSrcFolder(subpath, originURL.replace("/src", difference), onSuccess);
        }
    }

    /**
     * Prompts the user to select a Java source folder to import, returns the path to the folder.
     */
    public String chooseSrcFolderDialogue(String initialFolder) {
        return fileUtil.getFolder(swingApp.getJFrame(), "Select one Java SRC folder", initialFolder).getAbsolutePath();
    }

    // LOAD FROM GITHUB FUNCTIONS:

    /**
     * Uses the URL of a GitHub repository to download and import the source code.
     * If the import is successful, the download path of the repository is returned.
     */
    public void loadFromGitHub(String url, Consumer<String> onSuccess) {
        try {
            var repoName = getRepoByURL(url);

            GitHub github = new GitHubBuilder().build();
            var repo = github.getRepository(repoName);

            System.out.println("\nFound GitHub repo: " + repo.getFullName());

            String mainBranchName = repo.getDefaultBranch();

            String originURL = "https://github.com/" + repo.getFullName() + "/blob/" + mainBranchName + "/src/";

            cancelRepoDownload = false;
            downloadOrReadAsync(repo, (contents) -> {
                loadFromGenericFolder(contents.getPath(), originURL, onSuccess);
            });

        } catch (IOException e) {e.printStackTrace();}
    }


    /**
     * Prompts the user to select a folder to clone a repository into
     */
    public String chooseCloneFolderDialogue(String initialFolder) {
        return fileUtil.getFolder(swingApp.getJFrame(), "Select a folder to clone the repository into", initialFolder).getAbsolutePath();
    }

    /**
     * Clones a repository from a URL into a folder, then loads the source code from the folder,
     * using the loadFromGenericFolder function. Executes the two tasks one after the other.
     * if the import is successful, the path of the folder is returned via onSuccess.
     */
    public void cloneAndLoadFromFolder(String gitUrl, Consumer<String> onSuccess) {
        String path = chooseCloneFolderDialogue(null).replace("\\", "/");
        String convertedUrl = JGitCloneRepository.convertToGitUrl(gitUrl);
        TaskIterator cloneTaskIterator = JGitCloneRepository.cloneGitTaskIterator(convertedUrl, path);
        dtm.execute(cloneTaskIterator, new TaskObserver() {
            @Override
            public void taskFinished(ObservableTask task) {}
            @Override
            public void allFinished(FinishStatus finishStatus) {
                loadFromGenericFolder(path, convertedUrl.replace(".git", ""), onSuccess);
            }
        });
    }

    /**
     * Extracts the name of the GitHub repository from the URL.
     */
    private String getRepoByURL(String url) {
        if (!isValidGitHubUrl(url)) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        var split = url.replaceAll(".*github.com/", "").split("\\?")[0].split("/");
        var string = split.length <= 1 ? split[0] : split[0] + "/" + split[1];
        return string.replace(".git", "").strip();
    }

    /**
     * Checks if the URL is a valid GitHub URL containing "github.com".
     */
    public boolean isValidGitHubUrl(String url) {
        return url.matches(".*github.com/.*");
    }

    /**
     * Downloads the source code of the GitHub repository asynchronously if it is not already downloaded.
     * If the download is successful, the download path of the repository is returned.
     * The download may be interrupted by the user with the cancel button.
     */
    private void downloadOrReadAsync(GHRepository repo, Consumer<File> onComplete) {
        final File[] folder = {null};
        dtm.execute(new TaskIterator(new AbstractTask() {
            @Override
            public void run(TaskMonitor taskMonitor) {
                taskMonitor.setTitle("Downloading " + repo.getFullName());
                folder[0] = downloadOrRead(repo);
            }
            @Override
            public void cancel() {
                cancelRepoDownload = true;
            }
        }), new TaskObserver() {
            public void taskFinished(ObservableTask task) {}
            public void allFinished(FinishStatus finishStatus) {
                if (finishStatus.getType() == FinishStatus.Type.SUCCEEDED)
                    onComplete.accept(folder[0]);
            }
        });
    }

    /**
     * Downloads the source code of the GitHub repository if it is not already downloaded.
     * If the download is successful, the download path of the repository is returned.
     * Warning: Blocks the current thread until the download is complete.
     */
    private File downloadOrRead(GHRepository repo) {
        if (cancelRepoDownload)
            throw new CancellationException("Cancelled by user");

        File tempDir = getTempDir(repo.getFullName());

        try (var entries = Files.list(tempDir.toPath())) {
            var entry = entries.findFirst();
            if (entry.isPresent())
                return entry.get().toFile();

            return repo.readZip(input -> {
                if (cancelRepoDownload) throw new CancellationException("Cancelled by user");
                ZipUtil.unpack(input, tempDir);
                System.out.println("Downloaded repo to: " + tempDir.getAbsolutePath());
                return downloadOrRead(repo);
            }, null);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the path to the temporary directory containing downloaded GitHub repositories.
     */
    private String tempDirPath() {
        String tempDir = System.getProperty("java.io.tmpdir");
        return tempDir + "/cytoscape-loaded-repos/";
    }

    /**
     * Returns the path to the temporary directory for a specific GitHub repository.
     */
    private File getTempDir(String repoName) {
        repoName = repoName.replace("/", "-");
        String subfolder = tempDirPath() + repoName + "/";
        File f = new File(subfolder);
        if (!f.exists()) {
            if (!f.mkdirs()) {
                throw new RuntimeException("Could not create temp directory: " + subfolder);
            }
        }
        return f;
    }

    /**
     * Clears the temporary directory containing downloaded GitHub repositories.
     */
    public void clearTempDir() {
        File f = new File(tempDirPath());
        if (f.exists()) {
            try {
                FileUtils.deleteDirectory(f);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Calculates the size of the temporary directory containing downloaded GitHub repositories.
     * Returns the size as a readable string, e.g. "1.2 MB".
     */
    public String getTempDirSize() {
        File f = new File(tempDirPath());
        if (f.exists()) {
            return readableFileSize(FileUtils.sizeOfDirectory(f));
        }
        return readableFileSize(0);
    }

    /**
     * Formats the size of a file as a readable string, e.g. "1.2 MB".
     * Source: <a href="https://stackoverflow.com/a/5599842/">stackoverflow.com</a>
     */
    private String readableFileSize(long size) {
        if(size <= 0) return "(empty)";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size)/Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size/Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
