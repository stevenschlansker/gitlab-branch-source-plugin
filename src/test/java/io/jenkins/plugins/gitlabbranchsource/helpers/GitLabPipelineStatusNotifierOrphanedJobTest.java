package io.jenkins.plugins.gitlabbranchsource.helpers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.scm.NullSCM;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.gitlabbranchsource.BranchSCMHead;
import io.jenkins.plugins.gitlabbranchsource.BranchSCMRevision;
import io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource;
import io.jenkins.plugins.gitlabbranchsource.GitLabSCMSourceBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitBranchSCMHead;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.impl.NullSCMSource;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

public class GitLabPipelineStatusNotifierOrphanedJobTest {

    private static final String SOURCE_ID = "gitlab-source";
    private static final String GIT_SOURCE_ID = "git-source";
    private static final String BRANCH = "feature";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private WorkflowMultiBranchProject project;
    private GitLabSCMSource source;

    @Before
    public void setUp() throws Exception {
        project = j.createProject(WorkflowMultiBranchProject.class, "project");
        source = new GitLabSCMSourceBuilder(SOURCE_ID, "server", "creds", "owner", "group/project", "project").build();
        project.getSourcesList().add(new BranchSource(source));
    }

    @Test
    public void resolves_source_of_live_branch_job() {
        WorkflowJob job = gitLabBranchJob(project);

        assertThat(GitLabPipelineStatusNotifier.getSource(run(job, revisionAction())), sameInstance(source));
    }

    @Test
    public void recovers_source_of_orphaned_branch_job_from_revision_action() {
        WorkflowJob job = orphan(project, gitLabBranchJob(project));
        assertThat(SCMSource.SourceByItem.findSource(job), instanceOf(NullSCMSource.class));

        assertThat(GitLabPipelineStatusNotifier.getSource(run(job, revisionAction())), sameInstance(source));
    }

    @Test
    public void warns_on_completion_of_orphaned_branch_job_without_revision() {
        WorkflowJob job = orphan(project, gitLabBranchJob(project));
        Run<?, ?> run = run(job);

        assertThat(GitLabPipelineStatusNotifier.getSource(run), nullValue());
        assertThat(sendNotifications(run, true), containsString("Cannot notify GitLab of the result of"));
        assertThat(sendNotifications(run, false), is(""));
    }

    @Test
    public void stays_silent_for_orphaned_branch_job_of_another_scm() throws Exception {
        WorkflowMultiBranchProject other = j.createProject(WorkflowMultiBranchProject.class, "other");
        other.getSourcesList().add(new BranchSource(gitSource()));
        WorkflowJob job = orphan(other, gitBranchJob(other));

        assertThat(GitLabPipelineStatusNotifier.getSource(run(job)), nullValue());
        assertThat(sendNotifications(run(job), true), is(""));
    }

    @Test
    public void stays_silent_for_orphaned_branch_job_of_another_scm_in_mixed_project() {
        project.getSourcesList().add(new BranchSource(gitSource()));
        WorkflowJob job = orphan(project, gitBranchJob(project));

        assertThat(GitLabPipelineStatusNotifier.getSource(run(job)), nullValue());
        assertThat(sendNotifications(run(job), true), is(""));
    }

    @Test
    public void stays_silent_for_job_outside_multibranch_project() throws Exception {
        FreeStyleProject freestyle = j.createFreeStyleProject();

        assertThat(GitLabPipelineStatusNotifier.getSource(run(freestyle)), nullValue());
        assertThat(sendNotifications(run(freestyle), true), is(""));
    }

    private static GitSCMSource gitSource() {
        GitSCMSource git = new GitSCMSource("https://example.com/repo.git");
        git.setId(GIT_SOURCE_ID);
        return git;
    }

    private static WorkflowJob gitLabBranchJob(WorkflowMultiBranchProject owner) {
        return branchJob(owner, SOURCE_ID, new BranchSCMHead(BRANCH));
    }

    private static WorkflowJob gitBranchJob(WorkflowMultiBranchProject owner) {
        return branchJob(owner, GIT_SOURCE_ID, new GitBranchSCMHead(BRANCH));
    }

    private static WorkflowJob branchJob(WorkflowMultiBranchProject owner, String sourceId, SCMHead head) {
        Branch branch = new Branch(sourceId, head, new NullSCM(), Collections.emptyList());
        return owner.getProjectFactory().newInstance(branch);
    }

    private static WorkflowJob orphan(WorkflowMultiBranchProject owner, WorkflowJob job) {
        BranchProjectFactory<WorkflowJob, ?> factory = owner.getProjectFactory();
        return factory.setBranch(job, new Branch.Dead(factory.getBranch(job)));
    }

    private SCMRevisionAction revisionAction() {
        return new SCMRevisionAction(
                source, new BranchSCMRevision(new BranchSCMHead(BRANCH), "0123456789abcdef0123456789abcdef01234567"));
    }

    private static Run<?, ?> run(Job<?, ?> job, SCMRevisionAction... actions) {
        Run<?, ?> run = Mockito.mock(Run.class);
        Mockito.doReturn(job).when(run).getParent();
        Mockito.doReturn(Arrays.asList(actions)).when(run).getActions(SCMRevisionAction.class);
        Mockito.doReturn(job.getFullDisplayName() + " #1").when(run).getFullDisplayName();
        return run;
    }

    private static String sendNotifications(Run<?, ?> run, boolean useResult) {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        GitLabPipelineStatusNotifier.sendNotifications(
                run, new StreamTaskListener(console, StandardCharsets.UTF_8), useResult);
        return console.toString(StandardCharsets.UTF_8);
    }
}
