package org.sonar.plugins.stash.client;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonar.plugins.stash.PullRequestRef;
import org.sonar.plugins.stash.StashPlugin.IssueType;
import org.sonar.plugins.stash.StashTest;
import org.sonar.plugins.stash.exceptions.StashClientException;
import org.sonar.plugins.stash.exceptions.StashReportExtractionException;
import org.sonar.plugins.stash.fixtures.WireMockExtension;
import org.sonar.plugins.stash.issue.StashComment;
import org.sonar.plugins.stash.issue.StashCommentReport;
import org.sonar.plugins.stash.issue.StashDiffReport;
import org.sonar.plugins.stash.issue.StashPullRequest;
import org.sonar.plugins.stash.issue.StashTask;
import org.sonar.plugins.stash.issue.StashUser;
import org.sonar.plugins.stash.issue.collector.DiffReportSample;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonar.plugins.stash.TestUtils.assertContains;
import static org.sonar.plugins.stash.TestUtils.primeWireMock;

import com.github.tomakehurst.wiremock.matching.EqualToPattern;

public class StashClientTest extends StashTest {
  private static final int timeout = 800;
  private static final int errorTimeout = timeout + 10;

  PullRequestRef pr = PullRequestRef.builder()
                                    .setProject("Project")
                                    .setRepository("Repository")
                                    .setPullRequestId(1)
                                    .build();

  @RegisterExtension
  public WireMockExtension wireMock = new WireMockExtension(WireMockConfiguration.options().dynamicPort());

  StashClient client;
  StashUser testUser = new StashUser(1, "userName", "userSlug", "email");

  @BeforeEach
  public void setUp() throws Exception {
    primeWireMock(wireMock);
    client = new StashClient("http://127.0.0.1:" + wireMock.port(),
                             new StashCredentials("login@email.com", "password", "login"),
                             timeout,
                             "dummyVersion");
  }

  @Test
  public void testPostCommentOnPullRequest() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HttpURLConnection.HTTP_CREATED)));

    client.postCommentOnPullRequest(pr, "Report");
  }

  @Test
  public void testGetBaseUrl() {
    assertEquals("http://127.0.0.1:" + wireMock.port(), client.getBaseUrl());
  }

  @Test
  public void testGetLogin() {
    assertEquals("login@email.com", client.getLogin());
  }

  @Test
  public void testPostCommentOnPullRequestWithWrongHTTPResult() throws Exception {
    addErrorResponse(any(anyUrl()), HTTP_NOT_IMPLEMENTED);

    try {
      client.postCommentOnPullRequest(pr, "Report");

      fail("Wrong HTTP result should raised StashClientException");

    } catch (StashClientException e) {
      assertContains(e.getMessage(), String.valueOf(HttpURLConnection.HTTP_NOT_IMPLEMENTED));
      assertContains(e.getMessage(), "detailed error");
      assertContains(e.getMessage(), "seriousException");
    }
  }

  @Test
  public void testPostCommentOnPullRequestWithException() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withFixedDelay(errorTimeout)));
    assertThrows(StashClientException.class, () ->
        client.postCommentOnPullRequest(pr, "Report")
    );
  }

  @Test
  public void testGetPullRequestComments() throws Exception {
    String stashJsonComment =
        "{\"values\": [{\"id\":1234, \"text\":\"message\", \"anchor\": {\"path\":\"path\", \"line\":5},"
        + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}]}";

    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(stashJsonComment)));

    StashCommentReport report = client.getPullRequestComments(pr, "path");

    assertTrue(report.contains("message", "path", 5));
    assertEquals(1, report.size());
  }

  @Test
  public void testGetPullRequestCommentsWithoutAuthor() throws Exception {
    String stashJsonComment = "{\"values\": [{\"id\":1234, \"text\":\"message\","
                              + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}]}";

    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(stashJsonComment)));

    assertThrows(StashReportExtractionException.class, () ->
        client.getPullRequestComments(pr, "path")
    );
  }

  @Test
  public void testGetPullRequestCommentsWithNextPage() throws Exception {
    String stashJsonComment1 =
        "{\"values\": [{\"id\":1234, \"text\":\"message1\", \"anchor\": {\"path\":\"path\", \"line\":1},"
        + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}], \"isLastPage\": false, \"nextPageStart\": 1}";

    String stashJsonComment2 =
        "{\"values\": [{\"id\":4321, \"text\":\"message2\", \"anchor\": {\"path\":\"path\", \"line\":2},"
        + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}], \"isLastPage\": true}";

    wireMock.stubFor(get(
        urlPathEqualTo("/rest/api/1.0/projects/Project/repos/Repository/pull-requests/1/comments"))
                         .withQueryParam("start", equalTo(String.valueOf(0))).willReturn(
            aJsonResponse().withStatus(HttpURLConnection.HTTP_OK).withBody(stashJsonComment1)
        ));

    wireMock.stubFor(get(
        urlPathEqualTo("/rest/api/1.0/projects/Project/repos/Repository/pull-requests/1/comments"))
                         .withQueryParam("start", equalTo(String.valueOf(1))).willReturn(
            aJsonResponse().withStatus(HttpURLConnection.HTTP_OK).withBody(stashJsonComment2)
        ));

    StashCommentReport report = client.getPullRequestComments(pr, "path");
    assertTrue(report.contains("message1", "path", 1));
    assertTrue(report.contains("message2", "path", 2));
    assertEquals(2, report.size());
  }

  @Test
  public void testGetPullRequestCommentsWithNoNextPage() throws Exception {
    String stashJsonComment1 =
        "{\"values\": [{\"id\":1234, \"text\":\"message1\", \"anchor\": {\"path\":\"path\", \"line\":5},"
        + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}], \"isLastPage\": true, \"nextPageStart\": 1}";

    String stashJsonComment2 =
        "{\"values\": [{\"id\":4321, \"text\":\"message2\", \"anchor\": {\"path\":\"path\", \"line\":10},"
        + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}], \"isLastPage\": true}";

    wireMock.stubFor(get(anyUrl()).withQueryParam("start", equalTo(String.valueOf(0))).willReturn(
        aJsonResponse().withStatus(HttpURLConnection.HTTP_OK).withBody(stashJsonComment1)));

    wireMock.stubFor(get(anyUrl()).withQueryParam("start", equalTo(String.valueOf(1))).willReturn(
        aJsonResponse().withStatus(HttpURLConnection.HTTP_OK).withBody(stashJsonComment2)));

    StashCommentReport report = client.getPullRequestComments(pr, "path");
    assertTrue(report.contains("message1", "path", 5));
    assertFalse(report.contains("message2", "path", 10));
    assertEquals(1, report.size());
  }

  @Test
  public void testGetPullRequestCommentsWithWrongHTTPResult() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_FORBIDDEN)));
    assertThrows(StashClientException.class, () ->
        client.getPullRequestComments(pr, "path")
    );
  }

  @Test
  public void testGetPullRequestCommentsWithWrongContentType() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aXMLResponse().withStatus(HTTP_OK)));
    assertThrows(StashClientException.class, () ->
        client.getPullRequestComments(pr, "path")
    );
  }

  @Test
  public void testGetPullRequestCommentsWithException() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withFixedDelay(errorTimeout)));
    assertThrows(StashClientException.class, () ->
        client.getPullRequestComments(pr, "path")
    );
  }

  @Test
  public void testGetPullRequestDiffs() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_OK)
                                                             .withBody(DiffReportSample.baseReport)));

    StashDiffReport report = client.getPullRequestDiffs(pr);
    assertEquals(4, report.getDiffs().size());
  }

  @Test
  public void testGetPullRequestDiffsWithMalformedTasks() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_OK)
                                                             .withBody(DiffReportSample.baseReportWithMalformedTasks)));

    assertThrows(StashClientException.class, () ->
        client.getPullRequestDiffs(pr)
    );
  }

  @Test
  public void testGetPullRequestDiffsWithWrongHTTPResult() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_FORBIDDEN)
                                                             .withBody(DiffReportSample.baseReport)));
    assertThrows(StashClientException.class, () ->
        client.getPullRequestDiffs(pr)
    );
  }

  @Test
  public void testGetPullRequestDiffsWithException() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withFixedDelay(errorTimeout)));
    assertThrows(StashClientException.class, () ->
        client.getPullRequestDiffs(pr)
    );
  }

  @Test
  public void testPostCommentLineOnPullRequest() throws Exception {
    String stashJsonComment = "{\"id\":1234, \"text\":\"message\", \"anchor\": {\"path\":\"path\", \"line\":5},"
                              + "\"author\": {\"id\":1, \"name\":\"SonarQube\", \"slug\":\"sonarqube\", \"email\":\"sq@email.com\"}, \"version\": 0}";
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_CREATED).withBody(stashJsonComment)));

    StashComment comment = client.postCommentLineOnPullRequest(pr, "message", "path", 5, IssueType.CONTEXT);
    assertEquals(1234, comment.getId());
  }

  @Test
  public void testPostCommentLineOnPullRequestWithWrongHTTPResult() throws Exception {
    addErrorResponse(any(anyUrl()), HTTP_FORBIDDEN);

    try {
      client.postCommentLineOnPullRequest(pr, "message", "path", 5, IssueType.CONTEXT);
      fail("Wrong HTTP result should raised StashClientException");
    } catch (StashClientException e) {
      assertContains(e.getMessage(), "detailed error");
      assertContains(e.getMessage(), "seriousException");
    }
  }

  @Test
  public void testPostCommentLineOnPullRequestWithException() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_CREATED).withFixedDelay(errorTimeout)));
    assertThrows(StashClientException.class, () ->
        client.postCommentLineOnPullRequest(pr, "message", "path", 5, IssueType.CONTEXT)
    );
  }

  @Test
  public void testGetUser() throws Exception {
    String jsonUser = "{\"name\":\"SonarQube\", \"email\":\"sq@email.com\", \"id\":1, \"slug\":\"sonarqube\"}";
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(jsonUser)));

    StashUser user = client.getUser("sonarqube");

    assertEquals(1, user.getId());
    assertEquals("SonarQube", user.getName());
    assertEquals("sq@email.com", user.getEmail());
    assertEquals("sonarqube", user.getSlug());

  }

  @Test
  public void testGetUserWithWrongHTTPResult() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_FORBIDDEN)));
    assertThrows(StashClientException.class, () ->
        client.getUser("sonarqube")
    );
  }

  @Test
  public void testDeletePullRequestComment() throws Exception {
    StashComment stashComment = new StashComment(1234, "message", "path", 42L, testUser, 0);
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_NO_CONTENT)));
    client.deletePullRequestComment(pr, stashComment);
    wireMock.verify(deleteRequestedFor(anyUrl()));
  }

  @Test
  public void testGetPullRequest() throws Exception {
    String jsonPullRequest = "{\"version\": 1, \"title\":\"PR-Test\", \"description\":\"PR-test\", \"reviewers\": []}";
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(jsonPullRequest)));

    StashPullRequest pullRequest = client.getPullRequest(pr);

    assertEquals(1, pullRequest.getId());
    assertEquals("Project", pullRequest.getProject());
    assertEquals("Repository", pullRequest.getRepository());
    assertEquals(1, pullRequest.getVersion());
  }


  @Test
  public void testApprovePullRequest() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse()));
    client.approvePullRequest(pr);
    wireMock.verify(postRequestedFor(anyUrl()));
  }


  @Test
  public void testResetPullRequestApproval() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse()));
    client.resetPullRequestApproval(pr);
    wireMock.verify(deleteRequestedFor(anyUrl()));
  }

  @Test
  public void testAddPullRequestReviewer() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse()));

    List<StashUser> reviewers = new ArrayList<>();
    reviewers.add(testUser);

    client.addPullRequestReviewer(pr, 1L, reviewers);
    wireMock.verify(putRequestedFor(anyUrl()));
  }

  @Test
  public void testAddPullRequestReviewerWithNoReviewer() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse()));
    client.addPullRequestReviewer(pr, 1L, new ArrayList<StashUser>());
    wireMock.verify(putRequestedFor(anyUrl()));
  }

  @Test
  public void testPostTaskOnComment() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_CREATED)));
    client.postTaskOnComment("message", 1111L);
    wireMock.verify(postRequestedFor(anyUrl()));
  }

  @Test
  public void testDeleteTaskOnComment() throws Exception {
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withStatus(HTTP_NO_CONTENT)));
    StashTask task = new StashTask(1111L, "some text", "some state", true);
    client.deleteTaskOnComment(task);
    wireMock.verify(deleteRequestedFor(anyUrl()));
  }

  @Test
  public void testFollowInternalRedirection() throws Exception {
    String jsonUser = "{\"name\":\"SonarQube\", \"email\":\"sq@email.com\", \"id\":1, \"slug\":\"sonarqube\"}";
    wireMock.stubFor(get(anyUrl()).atPriority(2).willReturn(
        aJsonResponse().withStatus(HTTP_MOVED_TEMP).withHeader("Location", "/foo")));
    wireMock.stubFor(get(urlPathEqualTo("/foo")).atPriority(1).willReturn(aJsonResponse().withBody(jsonUser)));
    client.getUser("does not matter");
    wireMock.verify(getRequestedFor(urlPathEqualTo("/foo")));
  }

  @Test
  public void testPullRequestHugePullRequestId() throws Exception {
    // See https://github.com/AmadeusITGroup/sonar-stash/issues/98
    int hugePullRequestId = 1234567890;

    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse()));

    PullRequestRef pr = PullRequestRef.builder()
                                      .setProject("Project")
                                      .setRepository("Repository")
                                      .setPullRequestId(hugePullRequestId)
                                      .build();

    client.getPullRequestComments(pr, "something");
    wireMock.verify(getRequestedFor(urlPathMatching(".*/pull-requests/1234567890/comments.*")));
  }

  @Test
  public void testClientWithoutCredentials() throws Exception {
    String jsonUser = "{\"name\":\"SonarQube\", \"email\":\"sq@email.com\", \"id\":1, \"slug\":\"sonarqube\"}";
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(jsonUser)));
    client = new StashClient("http://127.0.0.1:" + wireMock.port(),
        new StashCredentials(null, null, null),
        timeout,
        "dummyVersion");
    client.getUser("test");
    wireMock.verify(getRequestedFor(anyUrl()).withoutHeader("Authorization"));
  }

  @Test
  public void testClientWithoutPassword() throws Exception {
    String jsonUser = "{\"name\":\"SonarQube\", \"email\":\"sq@email.com\", \"id\":1, \"slug\":\"sonarqube\"}";
    wireMock.stubFor(any(anyUrl()).willReturn(aJsonResponse().withBody(jsonUser)));
    client = new StashClient("http://127.0.0.1:" + wireMock.port(),
        new StashCredentials("foo", null, "foo"),
        timeout,
        "dummyVersion");
    client.getUser("test");
    wireMock.verify(getRequestedFor(anyUrl()).withHeader("Authorization", new EqualToPattern("Basic Zm9vOg==")));
  }

  private void addErrorResponse(MappingBuilder mapping, int statusCode) {
    wireMock.stubFor(mapping.willReturn(aJsonResponse()
                                            .withStatus(statusCode)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody("{\n" +
                                                      "    \"errors\": [\n" +
                                                      "        {\n" +
                                                      "            \"context\": null,\n" +
                                                      "            \"message\": \"A detailed error message.\",\n" +
                                                      "            \"exceptionName\": \"seriousException\"\n" +
                                                      "        }\n" +
                                                      "    ]\n" +
                                                      "}")
    ));
  }

  public static ResponseDefinitionBuilder aJsonResponse() {
    return aResponse().withHeader("Content-Type", "application/json").withBody("{}");
  }

  public static ResponseDefinitionBuilder aXMLResponse() {
    return aResponse().withHeader("Content-Type", "application/xml")
                      .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><empty/>");
  }
}
