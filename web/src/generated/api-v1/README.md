# @mindtable/bitbucket-helper-api-v1@1.0.0

A TypeScript SDK client for the localhost API.

## Usage

First, install the SDK from npm.

```bash
npm install @mindtable/bitbucket-helper-api-v1 --save
```

Next, try it out.


```ts
import {
  Configuration,
  ActionItemsApi,
} from '@mindtable/bitbucket-helper-api-v1';
import type { AcknowledgeActionItemOperationRequest } from '@mindtable/bitbucket-helper-api-v1';

async function example() {
  console.log("🚀 Testing @mindtable/bitbucket-helper-api-v1 SDK...");
  const api = new ActionItemsApi();

  const body = {
    // string
    actionItemId: actionItemId_example,
    // AcknowledgeActionItemRequest
    acknowledgeActionItemRequest: ...,
    // string | Required for browser mutations and omitted on the trusted Unix transport. (optional)
    xCSRFToken: xCSRFToken_example,
  } satisfies AcknowledgeActionItemOperationRequest;

  try {
    const data = await api.acknowledgeActionItem(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```


## Documentation

### API Endpoints

All URIs are relative to */api/v1*

| Class | Method | HTTP request | Description
| ----- | ------ | ------------ | -------------
*ActionItemsApi* | [**acknowledgeActionItem**](docs/ActionItemsApi.md#acknowledgeactionitemoperation) | **PUT** /action-items/{actionItemId}/acknowledgment |
*ActionItemsApi* | [**getLiveActivityContent**](docs/ActionItemsApi.md#getliveactivitycontent) | **GET** /action-items/{actionItemId}/content |
*BrowserSecurityApi* | [**getBrowserSession**](docs/BrowserSecurityApi.md#getbrowsersession) | **GET** /browser-session |
*ConfigurationApi* | [**addRepository**](docs/ConfigurationApi.md#addrepositoryoperation) | **POST** /configuration/workspace/repositories |
*ConfigurationApi* | [**configureWorkspace**](docs/ConfigurationApi.md#configureworkspaceoperation) | **PUT** /configuration/workspace |
*ConfigurationApi* | [**getWorkspaceConfiguration**](docs/ConfigurationApi.md#getworkspaceconfiguration) | **GET** /configuration/workspace |
*ConfigurationApi* | [**removeRepository**](docs/ConfigurationApi.md#removerepository) | **DELETE** /configuration/workspace/repositories/{repositoryId} |
*DashboardApi* | [**getDashboard**](docs/DashboardApi.md#getdashboard) | **GET** /dashboard |
*HealthApi* | [**getHealth**](docs/HealthApi.md#gethealth) | **GET** /health |
*InboxApi* | [**getInbox**](docs/InboxApi.md#getinbox) | **GET** /inbox |
*PullRequestsApi* | [**getPullRequest**](docs/PullRequestsApi.md#getpullrequest) | **GET** /pull-requests/{pullRequestId} |
*PullRequestsApi* | [**listPullRequests**](docs/PullRequestsApi.md#listpullrequests) | **GET** /pull-requests |
*RefreshApi* | [**getRefreshRun**](docs/RefreshApi.md#getrefreshrun) | **GET** /refresh-runs/{refreshRunId} |
*RefreshApi* | [**startRefreshRun**](docs/RefreshApi.md#startrefreshrunoperation) | **POST** /refresh-runs |
*SynchronizationApi* | [**getSynchronization**](docs/SynchronizationApi.md#getsynchronization) | **GET** /synchronization |


### Models

- [AcknowledgeActionItemRequest](docs/AcknowledgeActionItemRequest.md)
- [AcknowledgeActionItemResponse](docs/AcknowledgeActionItemResponse.md)
- [AcknowledgeActionItemResult](docs/AcknowledgeActionItemResult.md)
- [AcknowledgedResult](docs/AcknowledgedResult.md)
- [AcknowledgmentRejectedResult](docs/AcknowledgmentRejectedResult.md)
- [AcknowledgmentStaleActivityVersionResult](docs/AcknowledgmentStaleActivityVersionResult.md)
- [ActionItem](docs/ActionItem.md)
- [ActionItemNotFoundResult](docs/ActionItemNotFoundResult.md)
- [ActionItemState](docs/ActionItemState.md)
- [Actor](docs/Actor.md)
- [AddRepositoryRequest](docs/AddRepositoryRequest.md)
- [AddRepositoryResponse](docs/AddRepositoryResponse.md)
- [AddRepositoryResult](docs/AddRepositoryResult.md)
- [AllConfiguredRepositoriesTarget](docs/AllConfiguredRepositoriesTarget.md)
- [AlreadyAcknowledgedResult](docs/AlreadyAcknowledgedResult.md)
- [ApiVersion](docs/ApiVersion.md)
- [BrowserSessionResponse](docs/BrowserSessionResponse.md)
- [BrowserSessionResult](docs/BrowserSessionResult.md)
- [Build](docs/Build.md)
- [BuildState](docs/BuildState.md)
- [ConfigureWorkspaceRequest](docs/ConfigureWorkspaceRequest.md)
- [ConfigureWorkspaceResponse](docs/ConfigureWorkspaceResponse.md)
- [ConfigureWorkspaceResult](docs/ConfigureWorkspaceResult.md)
- [ConfiguredRepository](docs/ConfiguredRepository.md)
- [ContentAvailableResult](docs/ContentAvailableResult.md)
- [ContentUnavailableResult](docs/ContentUnavailableResult.md)
- [DashboardResponse](docs/DashboardResponse.md)
- [DashboardResult](docs/DashboardResult.md)
- [DashboardSnapshot](docs/DashboardSnapshot.md)
- [DashboardSnapshotChangedResult](docs/DashboardSnapshotChangedResult.md)
- [DashboardSnapshotUnchangedResult](docs/DashboardSnapshotUnchangedResult.md)
- [Freshness](docs/Freshness.md)
- [FreshnessFresh](docs/FreshnessFresh.md)
- [FreshnessNeverSynchronized](docs/FreshnessNeverSynchronized.md)
- [FreshnessStale](docs/FreshnessStale.md)
- [GatewayFailure](docs/GatewayFailure.md)
- [GatewayFailureCategory](docs/GatewayFailureCategory.md)
- [GetRefreshRunResponse](docs/GetRefreshRunResponse.md)
- [GetRefreshRunResult](docs/GetRefreshRunResult.md)
- [GetWorkspaceConfigurationResponse](docs/GetWorkspaceConfigurationResponse.md)
- [GetWorkspaceConfigurationResult](docs/GetWorkspaceConfigurationResult.md)
- [HealthComponent](docs/HealthComponent.md)
- [HealthComponentName](docs/HealthComponentName.md)
- [HealthResponse](docs/HealthResponse.md)
- [HealthSnapshotResult](docs/HealthSnapshotResult.md)
- [HealthStatus](docs/HealthStatus.md)
- [Inbox](docs/Inbox.md)
- [InboxAvailableResult](docs/InboxAvailableResult.md)
- [InboxResponse](docs/InboxResponse.md)
- [InboxResult](docs/InboxResult.md)
- [LiveActivityContentResponse](docs/LiveActivityContentResponse.md)
- [LiveActivityContentResult](docs/LiveActivityContentResult.md)
- [LiveContentUnavailableReason](docs/LiveContentUnavailableReason.md)
- [LiveStaleActivityVersionResult](docs/LiveStaleActivityVersionResult.md)
- [NewerActivityObservedResult](docs/NewerActivityObservedResult.md)
- [NoRepositoriesConfiguredResult](docs/NoRepositoriesConfiguredResult.md)
- [PartialFailure](docs/PartialFailure.md)
- [Polling](docs/Polling.md)
- [PollingActive](docs/PollingActive.md)
- [PollingIdle](docs/PollingIdle.md)
- [PullRequestCard](docs/PullRequestCard.md)
- [PullRequestDetail](docs/PullRequestDetail.md)
- [PullRequestDetailResponse](docs/PullRequestDetailResponse.md)
- [PullRequestDetailResult](docs/PullRequestDetailResult.md)
- [PullRequestFoundResult](docs/PullRequestFoundResult.md)
- [PullRequestListResponse](docs/PullRequestListResponse.md)
- [PullRequestListResult](docs/PullRequestListResult.md)
- [PullRequestNotFoundResult](docs/PullRequestNotFoundResult.md)
- [PullRequestsAvailableResult](docs/PullRequestsAvailableResult.md)
- [Readiness](docs/Readiness.md)
- [ReadinessAvailable](docs/ReadinessAvailable.md)
- [ReadinessCheck](docs/ReadinessCheck.md)
- [ReadinessSummary](docs/ReadinessSummary.md)
- [ReadinessUnavailable](docs/ReadinessUnavailable.md)
- [RefreshDeferredByBackoffDisposition](docs/RefreshDeferredByBackoffDisposition.md)
- [RefreshDeferredByBackoffRepository](docs/RefreshDeferredByBackoffRepository.md)
- [RefreshFailedRepository](docs/RefreshFailedRepository.md)
- [RefreshJoinedExistingDisposition](docs/RefreshJoinedExistingDisposition.md)
- [RefreshPartialFailureRepository](docs/RefreshPartialFailureRepository.md)
- [RefreshQueuedRepository](docs/RefreshQueuedRepository.md)
- [RefreshRegistrationDisposition](docs/RefreshRegistrationDisposition.md)
- [RefreshRepositoryNotConfiguredDisposition](docs/RefreshRepositoryNotConfiguredDisposition.md)
- [RefreshRun](docs/RefreshRun.md)
- [RefreshRunCompletedResult](docs/RefreshRunCompletedResult.md)
- [RefreshRunInProgressResult](docs/RefreshRunInProgressResult.md)
- [RefreshRunRegisteredResult](docs/RefreshRunRegisteredResult.md)
- [RefreshRunRepository](docs/RefreshRunRepository.md)
- [RefreshRunUnavailableResult](docs/RefreshRunUnavailableResult.md)
- [RefreshRunningRepository](docs/RefreshRunningRepository.md)
- [RefreshStartedDisposition](docs/RefreshStartedDisposition.md)
- [RefreshSucceededRepository](docs/RefreshSucceededRepository.md)
- [RefreshTarget](docs/RefreshTarget.md)
- [RemoveRepositoryResponse](docs/RemoveRepositoryResponse.md)
- [RemoveRepositoryResult](docs/RemoveRepositoryResult.md)
- [RepositoriesTarget](docs/RepositoriesTarget.md)
- [RepositoryAddedResult](docs/RepositoryAddedResult.md)
- [RepositoryAlreadyConfiguredResult](docs/RepositoryAlreadyConfiguredResult.md)
- [RepositoryGroup](docs/RepositoryGroup.md)
- [RepositoryNotConfiguredResult](docs/RepositoryNotConfiguredResult.md)
- [RepositoryNotFoundResult](docs/RepositoryNotFoundResult.md)
- [RepositoryRemovedResult](docs/RepositoryRemovedResult.md)
- [RepositoryResolutionUnavailableResult](docs/RepositoryResolutionUnavailableResult.md)
- [RequestError](docs/RequestError.md)
- [RequestErrorCode](docs/RequestErrorCode.md)
- [RequestErrorEnvelope](docs/RequestErrorEnvelope.md)
- [RequestViolation](docs/RequestViolation.md)
- [StartRefreshRunRequest](docs/StartRefreshRunRequest.md)
- [StartRefreshRunResponse](docs/StartRefreshRunResponse.md)
- [StartRefreshRunResult](docs/StartRefreshRunResult.md)
- [Synchronization](docs/Synchronization.md)
- [SynchronizationActivity](docs/SynchronizationActivity.md)
- [SynchronizationAttemptOutcome](docs/SynchronizationAttemptOutcome.md)
- [SynchronizationAvailableResult](docs/SynchronizationAvailableResult.md)
- [SynchronizationFailure](docs/SynchronizationFailure.md)
- [SynchronizationFailureCategory](docs/SynchronizationFailureCategory.md)
- [SynchronizationProblem](docs/SynchronizationProblem.md)
- [SynchronizationProblemNone](docs/SynchronizationProblemNone.md)
- [SynchronizationProblemPresent](docs/SynchronizationProblemPresent.md)
- [SynchronizationResponse](docs/SynchronizationResponse.md)
- [SynchronizationResult](docs/SynchronizationResult.md)
- [WorkspaceAlreadyConfiguredResult](docs/WorkspaceAlreadyConfiguredResult.md)
- [WorkspaceConfiguration](docs/WorkspaceConfiguration.md)
- [WorkspaceConfigurationAvailableResult](docs/WorkspaceConfigurationAvailableResult.md)
- [WorkspaceConfiguredResult](docs/WorkspaceConfiguredResult.md)
- [WorkspaceIdentityMismatchResult](docs/WorkspaceIdentityMismatchResult.md)
- [WorkspaceNotConfiguredResult](docs/WorkspaceNotConfiguredResult.md)
- [WorkspaceNotFoundResult](docs/WorkspaceNotFoundResult.md)
- [WorkspaceResolutionUnavailableResult](docs/WorkspaceResolutionUnavailableResult.md)

### Authorization

Endpoints do not require authorization.


## About

This TypeScript SDK client supports the [Fetch API](https://fetch.spec.whatwg.org/)
and is automatically generated by the
[OpenAPI Generator](https://openapi-generator.tech) project:

- API version: `1.0.0`
- Package version: `1.0.0`
- Generator version: `7.24.0`
- Build package: `org.openapitools.codegen.languages.TypeScriptFetchClientCodegen`

The generated npm module supports the following:

- Environments
  * Node.js
  * Webpack
  * Browserify
- Language levels
  * ES5 - you must have a Promises/A+ library installed
  * ES6
- Module systems
  * CommonJS
  * ES6 module system


## Development

### Building

To build the TypeScript source code, you need to have Node.js and npm installed.
After cloning the repository, navigate to the project directory and run:

```bash
npm install
npm run build
```

### Publishing

Once you've built the package, you can publish it to npm:

```bash
npm publish
```

## License

[]()
