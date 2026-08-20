package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

private typealias M = JooqRecordMappings

internal data class JooqPersistenceSeams(
    val rollbackAction: (Connection) -> Unit = { it.rollback() },
)

class JooqApplicationPersistence private constructor(
    private val database: SqliteDatabase,
    private val recorder: BackendEventRecorder,
    private val seams: JooqPersistenceSeams,
) : ApplicationTransactionRunner, AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean()
    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T {
        check(!closed.get()) { "Persistence is closed" }
        return mutex.withLock {
            check(!closed.get()) { "Persistence is closed" }
            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                try { block(Transaction(DSL.using(connection, SQLDialect.SQLITE))).also { connection.commit() } }
                catch (failure: Throwable) {
                    val propagatedFailure = try {
                        seams.rollbackAction(connection)
                        failure
                    } catch (rollbackFailure: Throwable) {
                        rollbackFailure
                    }
                    try {
                        recorder.record(BackendLogEvent.PersistenceTransactionFailed("transaction", propagatedFailure))
                    } catch (loggingFailure: Throwable) {
                        if (loggingFailure !== propagatedFailure) propagatedFailure.addSuppressed(loggingFailure)
                    }
                    throw propagatedFailure
                }
            }
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) database.close() }
    companion object {
        fun open(
            path: Path,
            recorder: BackendEventRecorder = BackendEventRecorder.NONE,
        ): JooqApplicationPersistence = open(path, recorder, JooqPersistenceSeams())

        internal fun open(
            path: Path,
            recorder: BackendEventRecorder,
            seams: JooqPersistenceSeams,
        ): JooqApplicationPersistence = SqliteDatabase.open(path)
            .also { it.migrate() }
            .let { JooqApplicationPersistence(it, recorder, seams) }
    }

    private class Transaction(private val dsl: DSLContext) : ApplicationTransaction {
        override val configurationStore = object : ConfigurationStore {
            override suspend fun find(): StoredInstallationConfiguration? {
                val row = dsl.fetchOne("SELECT * FROM installation_configuration WHERE singleton_id=1") ?: return null
                return M.configuration(row, dsl.fetch("SELECT * FROM configured_repository WHERE workspace_id=? ORDER BY position,id", row.get("workspace_id")).map(M::repository))
            }
            override suspend fun save(v: StoredInstallationConfiguration) {
                dsl.execute("DELETE FROM installation_configuration WHERE singleton_id=1")
                dsl.execute("INSERT INTO installation_configuration VALUES(1,?,?,?,?,?,?,?,?,?)", v.workspaceId.value,v.bitbucketApiBaseUrl.toString(),v.workspaceSlug,v.workspaceDisplayName,v.workspaceWebUrl.toString(),v.currentUserStableId,v.currentUserDisplayName,M.text(v.configuredAt),v.retentionDays)
                v.repositories.forEachIndexed { p,r -> dsl.execute("INSERT INTO configured_repository VALUES(?,?,?,?,?,?,?)",r.id.value,r.workspaceId.value,r.slug,r.displayName,r.webUrl.toString(),M.text(r.removedAt),p) }
            }
        }
        override val pullRequestStore = object : PullRequestStore {
            override suspend fun find(id: PullRequestId)=loadPr(id.value)
            override suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean)=dsl.fetch("SELECT id FROM pull_request WHERE repository_id=? ${if(includeInactive) "" else "AND active=1"} ORDER BY id",repositoryId.value).mapNotNull { loadPr(it.get("id",String::class.java)) }
            override suspend fun save(v: StoredPullRequestSnapshot) {
                val a=v.readiness as? StoredReadiness.Available; val u=v.readiness as? StoredReadiness.Unavailable
                dsl.execute("""INSERT INTO pull_request VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET repository_id=excluded.repository_id,upstream_number=excluded.upstream_number,title=excluded.title,author_stable_id=excluded.author_stable_id,author_display_name=excluded.author_display_name,draft=excluded.draft,head_commit=excluded.head_commit,web_url=excluded.web_url,created_at=excluded.created_at,updated_at=excluded.updated_at,observed_at=excluded.observed_at,active=excluded.active,inactive_at=excluded.inactive_at,readiness_kind=excluded.readiness_kind,readiness_passed=excluded.readiness_passed,readiness_total=excluded.readiness_total,readiness_safe_reason=excluded.readiness_safe_reason,builds_were_green=excluded.builds_were_green""",v.id.value,v.repositoryId.value,v.upstreamNumber,v.title,v.authorStableId,v.authorDisplayName,v.draft,v.headCommit,v.webUrl.toString(),M.text(v.createdAt),M.text(v.updatedAt),M.text(v.observedAt),v.active,M.text(v.inactiveAt),if(a==null)"UNAVAILABLE" else "AVAILABLE",a?.passed,a?.total,u?.safeReason,v.buildsWereGreen)
                dsl.execute("DELETE FROM readiness_check WHERE pull_request_id=?",v.id.value); a?.checks?.forEachIndexed { p,c->dsl.execute("INSERT INTO readiness_check VALUES(?,?,?,?,?)",v.id.value,p,c.name,c.passed,c.safeReason) }
                dsl.execute("DELETE FROM build_observation WHERE pull_request_id=?",v.id.value); v.builds.forEachIndexed { p,b->dsl.execute("INSERT INTO build_observation VALUES(?,?,?,?,?)",v.id.value,p,b.key,b.state.name,M.text(b.observedAt)) }
            }
            override suspend fun markMissingInactive(repositoryId: RepositoryId, activePullRequestIds: Set<PullRequestId>, authoritativeAt: Instant): List<StoredPullRequestSnapshot> {
                val candidates = dsl.fetch(
                    "SELECT id,observed_at FROM pull_request WHERE repository_id=? AND active=1 ORDER BY id",
                    repositoryId.value,
                ).filter { record ->
                    val id = record.get("id",String::class.java)
                    activePullRequestIds.none { it.value == id } &&
                        requireNotNull(M.instant(record.get("observed_at",String::class.java))) <= authoritativeAt
                }
                val changedIds = candidates.mapNotNull { record ->
                    val id = record.get("id",String::class.java)
                    val changed = dsl.execute(
                        "UPDATE pull_request SET active=0,inactive_at=?,observed_at=? WHERE id=? AND active=1 AND observed_at=?",
                        M.text(authoritativeAt),M.text(authoritativeAt),id,record.get("observed_at",String::class.java),
                    )
                    id.takeIf { changed == 1 }
                }
                return changedIds.mapNotNull(::loadPr)
            }
            override suspend fun markInactive(id: PullRequestId,inactiveAt: Instant){dsl.execute("UPDATE pull_request SET active=0,inactive_at=?,observed_at=? WHERE id=? AND active=1",M.text(inactiveAt),M.text(inactiveAt),id.value)}
            override suspend fun listInactiveBefore(cutoff: Instant)=dsl.fetch("SELECT id FROM pull_request WHERE active=0 AND inactive_at IS NOT NULL ORDER BY id")
                .mapNotNull { loadPr(it.get("id",String::class.java)) }
                .filter { requireNotNull(it.inactiveAt) < cutoff }
            override suspend fun delete(id: PullRequestId){dsl.execute("DELETE FROM action_item WHERE pull_request_id=?",id.value);dsl.execute("DELETE FROM pull_request WHERE id=?",id.value)}
        }
        override val actionItemStore = object : ActionItemStore {
            override suspend fun find(id: ActionItemId)=dsl.fetchOne("SELECT * FROM action_item WHERE id=?",id.value)?.let(M::actionItem)
            override suspend fun listByPullRequest(pullRequestId: PullRequestId)=dsl.fetch("SELECT * FROM action_item WHERE pull_request_id=? ORDER BY id",pullRequestId.value).map(M::actionItem)
            override suspend fun listActionable()=dsl.fetch("SELECT * FROM action_item WHERE state='OPEN' AND (acknowledged_version IS NULL OR acknowledged_version<>activity_version) ORDER BY id").map(M::actionItem)
            override suspend fun save(v: StoredActionItemSnapshot){dsl.execute("""INSERT INTO action_item VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET pull_request_id=excluded.pull_request_id,repository_id=excluded.repository_id,source_kind=excluded.source_kind,upstream_source_id=excluded.upstream_source_id,actor_stable_id=excluded.actor_stable_id,actor_display_name=excluded.actor_display_name,activity_at=excluded.activity_at,observed_at=excluded.observed_at,activity_version=excluded.activity_version,state=excluded.state,acknowledged_version=excluded.acknowledged_version,acknowledged_at=excluded.acknowledged_at,web_url=excluded.web_url""",v.id.value,v.pullRequestId.value,v.repositoryId.value,v.sourceKind,v.upstreamSourceId,v.actorStableId,v.actorDisplayName,M.text(v.activityAt),M.text(v.observedAt),v.activityVersion.value,v.state.name,v.acknowledgedVersion?.value,M.text(v.acknowledgedAt),v.webUrl.toString())}
            override suspend fun acknowledge(id: ActionItemId,expectedVersion: ActivityVersion,acknowledgedAt: Instant): StoredAcknowledgmentResult {
                val current = find(id)
                val monotonicAcknowledgedAt = maxOf(acknowledgedAt, current?.activityAt ?: acknowledgedAt)
                val changed=dsl.execute("UPDATE action_item SET state='ACKNOWLEDGED',acknowledged_version=?,acknowledged_at=? WHERE id=? AND activity_version=? AND state='OPEN' AND (acknowledged_version IS NULL OR acknowledged_version<>?)",expectedVersion.value,M.text(monotonicAcknowledgedAt),id.value,expectedVersion.value,expectedVersion.value)
                val fresh=find(id)?:return StoredAcknowledgmentResult.Missing
                if(changed==1)return StoredAcknowledgmentResult.Updated(fresh)
                if(fresh.activityVersion!=expectedVersion)return StoredAcknowledgmentResult.VersionMismatch(fresh)
                if(fresh.acknowledgedVersion==expectedVersion)return StoredAcknowledgmentResult.AlreadyApplied(fresh)
                return StoredAcknowledgmentResult.NotActionable(fresh)
            }
            override suspend fun deleteByPullRequest(pullRequestId: PullRequestId){dsl.execute("DELETE FROM action_item WHERE pull_request_id=?",pullRequestId.value)}
        }
        override val synchronizationCheckpointStore = object : SynchronizationCheckpointStore {
            override suspend fun find(repositoryId: RepositoryId)=loadSync(repositoryId.value)
            override suspend fun list()=dsl.fetch("SELECT repository_id FROM synchronization_checkpoint ORDER BY repository_id").mapNotNull { loadSync(it.get("repository_id",String::class.java)) }
            override suspend fun save(v: StoredSynchronizationSnapshot){val p=v.problem as? SynchronizationProblem.Present; dsl.execute("""INSERT INTO synchronization_checkpoint VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(repository_id) DO UPDATE SET activity=excluded.activity,last_attempt_at=excluded.last_attempt_at,last_attempt_outcome=excluded.last_attempt_outcome,last_success_at=excluded.last_success_at,snapshot_at=excluded.snapshot_at,problem_kind=excluded.problem_kind,attempted_count=excluded.attempted_count,succeeded_count=excluded.succeeded_count,consecutive_failure_count=excluded.consecutive_failure_count,backoff_until=excluded.backoff_until,pull_request_cursor=excluded.pull_request_cursor,activity_cursor=excluded.activity_cursor""",v.repositoryId.value,v.activity.name,M.text(v.lastAttemptAt),v.lastAttemptOutcome?.name,M.text(v.lastSuccessAt),M.text(v.snapshotAt),if(p==null)"NONE" else "PRESENT",p?.metadata?.attemptedCount,p?.metadata?.succeededCount,v.consecutiveFailureCount,M.text(v.backoffUntil),v.pullRequestCursor,v.activityCursor); dsl.execute("DELETE FROM synchronization_failure WHERE repository_id=?",v.repositoryId.value); p?.metadata?.failures?.forEachIndexed { i,f->dsl.execute("INSERT INTO synchronization_failure VALUES(?,?,?,?,?)",v.repositoryId.value,i,f.category.name,f.retryable,M.text(f.retryAt)) }}
        }
        override val notificationIntentStore = object : NotificationIntentStore {
            override suspend fun insertIfAbsent(v: StoredNotificationIntent): NotificationIntentInsertResult { find(v.id)?.let { return NotificationIntentInsertResult.Existing(it) }; val n=dsl.execute("INSERT OR IGNORE INTO notification_intent VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",v.id.value,v.request.deliveryKey.value,v.request.title,v.request.body,v.request.openUrl?.toString(),v.request.sound.name,M.text(v.createdAt),v.state.name,v.attemptCount,M.text(v.nextAttemptAt),v.lease?.owner,M.text(v.lease?.acquiredAt),M.text(v.lease?.expiresAt)); val s=find(v.id)!!; return if(n==1)NotificationIntentInsertResult.Inserted(s) else NotificationIntentInsertResult.Existing(s) }
            override suspend fun find(id: NotificationIntentId)=dsl.fetchOne("SELECT * FROM notification_intent WHERE id=?",id.value)?.let(M::intent)
            override suspend fun findDue(now: Instant,limit: Int): List<StoredNotificationIntent> {
                if (limit <= 0) return emptyList()
                return dsl.fetch("SELECT * FROM notification_intent WHERE state='PENDING'").map(M::intent)
                    .filter { intent ->
                        (intent.nextAttemptAt == null || intent.nextAttemptAt <= now) &&
                            (intent.lease == null || intent.lease.expiresAt <= now)
                    }
                    .sortedWith(compareBy<StoredNotificationIntent>({ it.nextAttemptAt != null }, { it.nextAttemptAt }, { it.createdAt }, { it.id.value }))
                    .take(limit)
            }
            override suspend fun tryClaim(id: NotificationIntentId,owner: String,acquiredAt: Instant,expiresAt: Instant): StoredNotificationIntent? {
                val record = dsl.fetchOne("SELECT * FROM notification_intent WHERE id=?", id.value) ?: return null
                val current = M.intent(record)
                if (current.state != NotificationIntentState.PENDING) return null
                val lease = current.lease
                if (lease != null && lease.owner != owner && lease.expiresAt > acquiredAt) return null
                val changed = if (lease == null) {
                    dsl.execute(
                        "UPDATE notification_intent SET lease_owner=?,lease_acquired_at=?,lease_expires_at=? WHERE id=? AND state='PENDING' AND lease_owner IS NULL",
                        owner,M.text(acquiredAt),M.text(expiresAt),id.value,
                    )
                } else {
                    dsl.execute(
                        "UPDATE notification_intent SET lease_owner=?,lease_acquired_at=?,lease_expires_at=? WHERE id=? AND state='PENDING' AND lease_owner=? AND lease_acquired_at=? AND lease_expires_at=?",
                        owner,M.text(acquiredAt),M.text(expiresAt),id.value,
                        record.get("lease_owner",String::class.java),record.get("lease_acquired_at",String::class.java),record.get("lease_expires_at",String::class.java),
                    )
                }
                return if (changed == 1) find(id) else null
            }
            override suspend fun releaseClaim(id: NotificationIntentId,owner: String)=dsl.execute("UPDATE notification_intent SET lease_owner=NULL,lease_acquired_at=NULL,lease_expires_at=NULL WHERE id=? AND lease_owner=?",id.value,owner)==1
            override suspend fun completeAttempt(id: NotificationIntentId,owner: String,completion: NotificationAttemptCompletion): Boolean {val a=completion.attempt;if(a.intentId!=id||a.attemptNumber<=0||find(id)==null)return false;val r=a.result;if(dsl.execute("INSERT OR IGNORE INTO notification_attempt VALUES(?,?,?,?,?,?,?)",a.id.value,id.value,a.attemptNumber,M.text(a.completedAt),if(r is NotificationDeliveryResult.Accepted)"ACCEPTED" else "FAILED",(r as? NotificationDeliveryResult.Failed)?.category?.name,(r as? NotificationDeliveryResult.Failed)?.ambiguous)!=1)return false;val changed=dsl.execute("UPDATE notification_intent SET state=?,attempt_count=?,next_attempt_at=?,lease_owner=NULL,lease_acquired_at=NULL,lease_expires_at=NULL WHERE id=? AND lease_owner=? AND attempt_count=?",completion.resultingState.name,a.attemptNumber,M.text(completion.nextAttemptAt),id.value,owner,a.attemptNumber-1);if(changed==1)return true;dsl.execute("DELETE FROM notification_attempt WHERE id=?",a.id.value);return false}
            override suspend fun listAttempts(id: NotificationIntentId)=dsl.fetch("SELECT * FROM notification_attempt WHERE intent_id=? ORDER BY attempt_number,id",id.value).map(M::attempt)
        }
        override val reminderProjectionStore = object : ReminderProjectionStore {
            override suspend fun listRepositoriesWithActionableItems()=dsl.fetch("SELECT DISTINCT r.id,r.display_name,r.web_url FROM configured_repository r JOIN pull_request p ON p.repository_id=r.id AND p.active=1 JOIN action_item a ON a.pull_request_id=p.id AND a.repository_id=r.id WHERE r.removed_at IS NULL AND a.state='OPEN' AND (a.acknowledged_version IS NULL OR a.acknowledged_version<>a.activity_version) ORDER BY r.id").map { ReminderRepositoryProjection(RepositoryId(it.get("id",String::class.java)),it.get("display_name",String::class.java),java.net.URI(it.get("web_url",String::class.java))) }
            override suspend fun listActionableItems(repositoryId: RepositoryId)=dsl.fetch("SELECT a.id,a.repository_id,a.activity_version,a.pull_request_id,a.activity_at FROM action_item a JOIN pull_request p ON p.id=a.pull_request_id JOIN configured_repository r ON r.id=a.repository_id WHERE a.repository_id=? AND r.removed_at IS NULL AND p.active=1 AND a.state='OPEN' AND (a.acknowledged_version IS NULL OR a.acknowledged_version<>a.activity_version)",repositoryId.value)
                .sortedWith(compareBy({ it.get("pull_request_id",String::class.java) }, { M.instant(it.get("activity_at",String::class.java)) }, { it.get("id",String::class.java) }))
                .map { ReminderActionItemProjection(ActionItemId(it.get("id",String::class.java)),RepositoryId(it.get("repository_id",String::class.java)),ActivityVersion(it.get("activity_version",String::class.java))) }
        }
        private fun loadPr(id:String):StoredPullRequestSnapshot?{val r=dsl.fetchOne("SELECT * FROM pull_request WHERE id=?",id)?:return null;return M.pullRequest(r,dsl.fetch("SELECT * FROM readiness_check WHERE pull_request_id=? ORDER BY position",id).map(M::readinessCheck),dsl.fetch("SELECT * FROM build_observation WHERE pull_request_id=? ORDER BY position",id).map(M::build))}
        private fun loadSync(id:String):StoredSynchronizationSnapshot?{val r=dsl.fetchOne("SELECT * FROM synchronization_checkpoint WHERE repository_id=?",id)?:return null;return M.synchronization(r,dsl.fetch("SELECT * FROM synchronization_failure WHERE repository_id=? ORDER BY position",id).map(M::failure))}
    }
}
