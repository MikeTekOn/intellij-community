// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.merge;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.util.BackgroundTaskUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vcs.merge.MergeProvider;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitPlatformFacade;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.StringScanner;
import org.jetbrains.annotations.CalledInBackground;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

import static com.intellij.dvcs.DvcsUtil.findVirtualFilesWithRefresh;
import static com.intellij.dvcs.DvcsUtil.sortVirtualFilesByPresentation;
import static com.intellij.openapi.vcs.VcsNotifier.IMPORTANT_ERROR_NOTIFICATION;

/**
 * The class is highly customizable, since the procedure of resolving conflicts is very common in Git operations.
 */
public class GitConflictResolver {

  private static final Logger LOG = Logger.getInstance(GitConflictResolver.class);

  @NotNull private final Collection<VirtualFile> myRoots;
  @NotNull private final Params myParams;

  @NotNull protected final Project myProject;
  @NotNull private final Git myGit;
  @NotNull private final GitRepositoryManager myRepositoryManager;
  @NotNull private final AbstractVcsHelper myVcsHelper;
  @NotNull private final GitVcs myVcs;

  /**
   * Customizing parameters - mostly String notification texts, etc.
   */
  public static class Params {
    private boolean reverse;
    private String myErrorNotificationTitle = "";
    private String myErrorNotificationAdditionalDescription = "";
    private String myMergeDescription = "";
    private MergeDialogCustomizer myMergeDialogCustomizer;

    public Params() {
      myMergeDialogCustomizer = new MergeDialogCustomizer() {
        @Override public String getMultipleFileMergeDescription(@NotNull Collection<VirtualFile> files) {
          return myMergeDescription;
        }
      };
    }

    public Params(Project project) {
      GitMergeProvider provider = (GitMergeProvider)GitVcs.getInstance(project).getMergeProvider();

      myMergeDialogCustomizer = new GitDefaultMergeDialogCustomizer(provider) {
        @Override public String getMultipleFileMergeDescription(@NotNull Collection<VirtualFile> files) {
          if (!StringUtil.isEmpty(myMergeDescription)) {
            return myMergeDescription;
          }
          return super.getMultipleFileMergeDescription(files);
        }
      };
    }

    /**
     * @param reverseMerge specify {@code true} if reverse merge provider has to be used for merging - it is the case of rebase or stash.
     */
    public Params setReverse(boolean reverseMerge) {
      reverse = reverseMerge;
      return this;
    }

    public Params setErrorNotificationTitle(String errorNotificationTitle) {
      myErrorNotificationTitle = errorNotificationTitle;
      return this;
    }

    public Params setErrorNotificationAdditionalDescription(String errorNotificationAdditionalDescription) {
      myErrorNotificationAdditionalDescription = errorNotificationAdditionalDescription;
      return this;
    }

    public Params setMergeDescription(String mergeDescription) {
      myMergeDescription = mergeDescription;
      return this;
    }

    public Params setMergeDialogCustomizer(MergeDialogCustomizer mergeDialogCustomizer) {
      myMergeDialogCustomizer = mergeDialogCustomizer;
      return this;
    }

  }

  /**
   * @deprecated To remove in IDEA 2017. Use {@link #GitConflictResolver(Project, Git, Collection, Params)}.
   */
  @SuppressWarnings("UnusedParameters")
  @Deprecated
  public GitConflictResolver(@NotNull Project project, @NotNull Git git, @NotNull GitPlatformFacade facade,
                             @NotNull Collection<VirtualFile> roots, @NotNull Params params) {
    this(project, git, roots, params);
  }

  public GitConflictResolver(@NotNull Project project, @NotNull Git git, @NotNull Collection<VirtualFile> roots, @NotNull Params params) {
    myProject = project;
    myGit = git;
    myRoots = roots;
    myParams = params;
    myRepositoryManager = GitUtil.getRepositoryManager(myProject);
    myVcsHelper = AbstractVcsHelper.getInstance(project);
    myVcs = GitVcs.getInstance(myProject);
  }

  /**
   * <p>
   *   Goes throw the procedure of merging conflicts via MergeTool for different types of operations.
   *   <ul>
   *     <li>Checks if there are unmerged files. If not, executes {@link #proceedIfNothingToMerge()}</li>
   *     <li>Otherwise shows a {@link com.intellij.openapi.vcs.merge.MultipleFileMergeDialog} where user is able to merge files.</li>
   *     <li>After the dialog is closed, checks if unmerged files remain.
   *         If everything is merged, executes {@link #proceedAfterAllMerged()}. Otherwise shows a notification.</li>
   *   </ul>
   * </p>
   * <p>
   *   If a Git error happens during seeking for unmerged files or in other cases,
   *   the method shows a notification and returns {@code false}.
   * </p>
   *
   * @return {@code true} if there is nothing to merge anymore, {@code false} if unmerged files remain or in the case of error.
   */
  public final boolean merge() {
    return merge(false);
  }

  /**
   * This is executed from {@link #merge()} if the initial check tells that there is nothing to merge.
   * In the basic implementation no action is performed, {@code true} is returned.
   * @return Return value is returned from {@link #merge()}
   */
  protected boolean proceedIfNothingToMerge() throws VcsException {
    return true;
  }

  /**
   * This is executed from {@link #merge()} after all conflicts are resolved.
   * In the basic implementation no action is performed, {@code true} is returned.
   * @return Return value is returned from {@link #merge()}
   */
  @CalledInBackground
  protected boolean proceedAfterAllMerged() throws VcsException {
    return true;
  }

  /**
   * Invoke the merge dialog, but execute nothing after merge is completed.
   * @return true if all changes were merged, false if unresolved merges remain.
   */
  public final boolean mergeNoProceed() {
    return merge(true);
  }

  /**
   * Shows notification that not all conflicts were resolved.
   */
  protected void notifyUnresolvedRemain() {
    notifyWarning(myParams.myErrorNotificationTitle,
                  "Unresolved conflicts remaining in the project." + myParams.myErrorNotificationAdditionalDescription);
  }

  /**
   * Shows notification that some conflicts were still not resolved - after user invoked the conflict resolver by pressing the link on the
   * notification.
   */
  private void notifyUnresolvedRemainAfterNotification() {
    notifyWarning("Unresolved Conflicts Remaining", myParams.myErrorNotificationAdditionalDescription);
  }

  protected void notifyWarning(String title, String content) {
    Notification notification = IMPORTANT_ERROR_NOTIFICATION.createNotification(title, content, NotificationType.WARNING, null);
    notification.addAction(NotificationAction.createSimple("Resolve...", () -> {
      notification.expire();
      BackgroundTaskUtil.executeOnPooledThread(myProject, () -> mergeNoProceed());
    }));
    VcsNotifier.getInstance(myProject).notify(notification);
  }

  private boolean merge(boolean mergeDialogInvokedFromNotification) {
    try {
      final Collection<VirtualFile> initiallyUnmergedFiles = getUnmergedFiles(myRoots);
      if (initiallyUnmergedFiles.isEmpty()) {
        LOG.info("merge: no unmerged files");
        return mergeDialogInvokedFromNotification ? true : proceedIfNothingToMerge();
      }
      else {
        showMergeDialog(initiallyUnmergedFiles);

        final Collection<VirtualFile> unmergedFilesAfterResolve = getUnmergedFiles(myRoots);
        if (unmergedFilesAfterResolve.isEmpty()) {
          LOG.info("merge no more unmerged files");
          return mergeDialogInvokedFromNotification ? true : proceedAfterAllMerged();
        } else {
          LOG.info("mergeFiles unmerged files remain: " + unmergedFilesAfterResolve);
          if (mergeDialogInvokedFromNotification) {
            notifyUnresolvedRemainAfterNotification();
          } else {
            notifyUnresolvedRemain();
          }
        }
      }
    } catch (VcsException e) {
      notifyException(e);
    }
    return false;

  }

  private void showMergeDialog(@NotNull final Collection<VirtualFile> initiallyUnmergedFiles) {
    TransactionGuard.getInstance().assertWriteSafeContext(ModalityState.defaultModalityState());
    ApplicationManager.getApplication().invokeAndWait(() -> {
      MergeProvider mergeProvider = new GitMergeProvider(myProject, myParams.reverse);
      myVcsHelper.showMergeDialog(new ArrayList<>(initiallyUnmergedFiles), mergeProvider, myParams.myMergeDialogCustomizer);
    });
  }

  private void notifyException(VcsException e) {
    LOG.info("mergeFiles ", e);
    final String description = "Couldn't check the working tree for unmerged files because of an error.";
    VcsNotifier.getInstance(myProject).notifyError(myParams.myErrorNotificationTitle,
                                                   description + myParams.myErrorNotificationAdditionalDescription + "<br/>" +
                                                   e.getLocalizedMessage());
  }

  /**
   * @return unmerged files in the given Git roots, all in a single collection.
   * @see #getUnmergedFiles(com.intellij.openapi.vfs.VirtualFile)
   */
  private Collection<VirtualFile> getUnmergedFiles(@NotNull Collection<VirtualFile> roots) throws VcsException {
    final Collection<VirtualFile> unmergedFiles = new HashSet<>();
    for (VirtualFile root : roots) {
      unmergedFiles.addAll(getUnmergedFiles(root));
    }
    return unmergedFiles;
  }

  /**
   * @return unmerged files in the given Git root.
   * @see #getUnmergedFiles(java.util.Collection
   */
  private Collection<VirtualFile> getUnmergedFiles(@NotNull VirtualFile root) throws VcsException {
    return unmergedFiles(root);
  }

  /**
   * Parse changes from lines
   *
   *
   * @param root    the git root
   * @return a set of unmerged files
   * @throws com.intellij.openapi.vcs.VcsException if the input format does not matches expected format
   */
  private List<VirtualFile> unmergedFiles(final VirtualFile root) throws VcsException {
    GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository not found for root " + root);
      return Collections.emptyList();
    }

    GitCommandResult result = myGit.getUnmergedFiles(repository);
    if (!result.success()) {
      throw new VcsException(result.getErrorOutputAsJoinedString());
    }

    String output = StringUtil.join(result.getOutput(), "\n");
    HashSet<String> unmergedPaths = ContainerUtil.newHashSet();
    for (StringScanner s = new StringScanner(output); s.hasMoreData();) {
      if (s.isEol()) {
        s.nextLine();
        continue;
      }
      s.boundedToken('\t');
      String relative = s.line();
      unmergedPaths.add(GitUtil.unescapePath(relative));
    }

    if (unmergedPaths.size() == 0) {
      return Collections.emptyList();
    }
    else {
      List<File> files = ContainerUtil.map(unmergedPaths, path -> new File(root.getPath(), path));
      return sortVirtualFilesByPresentation(findVirtualFilesWithRefresh(files));
    }
  }

}
