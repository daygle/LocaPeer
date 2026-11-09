package com.locapeer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.locapeer.data.entity.ContactAddReceiptEntity

@Dao
interface ContactAddReceiptDao {
    @Query("SELECT * FROM contact_add_receipts WHERE receiptId = :receiptId")
    suspend fun get(receiptId: String): ContactAddReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: ContactAddReceiptEntity)

    @Query("DELETE FROM contact_add_receipts WHERE receiptId = :receiptId")
    suspend fun delete(receiptId: String)

    /** Outbound: mark the track request published, so a resume does not send it again. */
    @Query("UPDATE contact_add_receipts SET requestSentAt = :sentAt WHERE receiptId = :receiptId")
    suspend fun markRequestSent(receiptId: String, sentAt: Long)

    /** Normally deleted right after the step; this only bounds rows leaked by a failed delete. */
    @Query("DELETE FROM contact_add_receipts WHERE MAX(COALESCE(appliedAt, 0), COALESCE(requestSentAt, 0)) < :cutoff")
    suspend fun deleteAppliedBefore(cutoff: Long)
}
