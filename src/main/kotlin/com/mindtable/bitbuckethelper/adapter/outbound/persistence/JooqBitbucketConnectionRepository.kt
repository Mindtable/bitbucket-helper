package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.model.ConnectionState
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketConnectionRepository
import java.time.Instant
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class JooqBitbucketConnectionRepository(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher,
) : BitbucketConnectionRepository {
    override suspend fun find(): BitbucketConnectionSnapshot? = withContext(dispatcher) {
        readSnapshot(DSL.using(dataSource, SQLDialect.SQLITE))
    }

    override suspend fun recordSuccess(
        account: BitbucketAccount,
        attemptedAt: Instant,
    ): BitbucketConnectionSnapshot = withContext(dispatcher) {
        DSL.using(dataSource, SQLDialect.SQLITE).transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            dsl.insertInto(BITBUCKET_CONNECTION_SNAPSHOT)
                .columns(
                    BITBUCKET_CONNECTION_SNAPSHOT.SINGLETON_ID,
                    BITBUCKET_CONNECTION_SNAPSHOT.STATE,
                    BITBUCKET_CONNECTION_SNAPSHOT.ACCOUNT_UUID,
                    BITBUCKET_CONNECTION_SNAPSHOT.DISPLAY_NAME,
                    BITBUCKET_CONNECTION_SNAPSHOT.NICKNAME,
                    BITBUCKET_CONNECTION_SNAPSHOT.LAST_ATTEMPT_AT,
                    BITBUCKET_CONNECTION_SNAPSHOT.LAST_SUCCESS_AT,
                    BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_CODE,
                    BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_MESSAGE,
                )
                .values(
                    SINGLETON_ID,
                    ConnectionState.HEALTHY.databaseValue(),
                    account.uuid,
                    account.displayName,
                    account.nickname,
                    attemptedAt.toString(),
                    attemptedAt.toString(),
                    null,
                    null,
                )
                .onConflict(BITBUCKET_CONNECTION_SNAPSHOT.SINGLETON_ID)
                .doUpdate()
                .set(BITBUCKET_CONNECTION_SNAPSHOT.STATE, ConnectionState.HEALTHY.databaseValue())
                .set(BITBUCKET_CONNECTION_SNAPSHOT.ACCOUNT_UUID, account.uuid)
                .set(BITBUCKET_CONNECTION_SNAPSHOT.DISPLAY_NAME, account.displayName)
                .set(BITBUCKET_CONNECTION_SNAPSHOT.NICKNAME, account.nickname)
                .set(BITBUCKET_CONNECTION_SNAPSHOT.LAST_ATTEMPT_AT, attemptedAt.toString())
                .set(BITBUCKET_CONNECTION_SNAPSHOT.LAST_SUCCESS_AT, attemptedAt.toString())
                .setNull(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_CODE)
                .setNull(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_MESSAGE)
                .execute()
            requireNotNull(readSnapshot(dsl))
        }
    }

    override suspend fun recordFailure(
        failure: ConnectionFailure,
        attemptedAt: Instant,
    ): BitbucketConnectionSnapshot = withContext(dispatcher) {
        DSL.using(dataSource, SQLDialect.SQLITE).transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            dsl.insertInto(BITBUCKET_CONNECTION_SNAPSHOT)
                .columns(
                    BITBUCKET_CONNECTION_SNAPSHOT.SINGLETON_ID,
                    BITBUCKET_CONNECTION_SNAPSHOT.STATE,
                    BITBUCKET_CONNECTION_SNAPSHOT.LAST_ATTEMPT_AT,
                    BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_CODE,
                    BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_MESSAGE,
                )
                .values(
                    SINGLETON_ID,
                    ConnectionState.FAILED.databaseValue(),
                    attemptedAt.toString(),
                    failure.code.databaseValue(),
                    failure.message,
                )
                .onConflict(BITBUCKET_CONNECTION_SNAPSHOT.SINGLETON_ID)
                .doUpdate()
                .set(BITBUCKET_CONNECTION_SNAPSHOT.STATE, ConnectionState.FAILED.databaseValue())
                .set(BITBUCKET_CONNECTION_SNAPSHOT.LAST_ATTEMPT_AT, attemptedAt.toString())
                .set(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_CODE, failure.code.databaseValue())
                .set(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_MESSAGE, failure.message)
                .execute()
            requireNotNull(readSnapshot(dsl))
        }
    }

    private fun readSnapshot(dsl: DSLContext): BitbucketConnectionSnapshot? =
        dsl.selectFrom(BITBUCKET_CONNECTION_SNAPSHOT)
            .where(BITBUCKET_CONNECTION_SNAPSHOT.SINGLETON_ID.eq(SINGLETON_ID))
            .fetchOne()
            ?.toSnapshot()

    private fun Record.toSnapshot(): BitbucketConnectionSnapshot {
        val failureCode = get(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_CODE)
        return BitbucketConnectionSnapshot(
            state = ConnectionState.valueOf(
                requireNotNull(get(BITBUCKET_CONNECTION_SNAPSHOT.STATE)).uppercase(),
            ),
            account = get(BITBUCKET_CONNECTION_SNAPSHOT.ACCOUNT_UUID)?.let { uuid ->
                BitbucketAccount(
                    uuid = uuid,
                    displayName = requireNotNull(get(BITBUCKET_CONNECTION_SNAPSHOT.DISPLAY_NAME)),
                    nickname = get(BITBUCKET_CONNECTION_SNAPSHOT.NICKNAME),
                )
            },
            lastAttemptAt = Instant.parse(
                requireNotNull(get(BITBUCKET_CONNECTION_SNAPSHOT.LAST_ATTEMPT_AT)),
            ),
            lastSuccessAt = get(BITBUCKET_CONNECTION_SNAPSHOT.LAST_SUCCESS_AT)?.let(Instant::parse),
            failure = failureCode?.let { code ->
                ConnectionFailure(
                    code.toFailureCode(),
                    requireNotNull(get(BITBUCKET_CONNECTION_SNAPSHOT.FAILURE_MESSAGE)),
                )
            },
        )
    }

    private companion object {
        const val SINGLETON_ID = 1
    }
}

private fun ConnectionState.databaseValue() = name.lowercase()

private fun ConnectionFailureCode.databaseValue() = name.lowercase()

private fun String.toFailureCode() = ConnectionFailureCode.valueOf(uppercase())
