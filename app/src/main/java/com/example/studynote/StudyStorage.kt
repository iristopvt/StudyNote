package com.example.studynote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 한 번 틀릴 때마다 남기는 기록
data class WrongAttempt(
    val answer: String,
    val timestamp: Long
)

// 문제별 오답 이력과 복습 완료 여부
data class WrongRecord(
    val questionId: String,
    val attempts: List<WrongAttempt>,
    val resolved: Boolean
)

class StudyStorage(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("study_note", Context.MODE_PRIVATE)

    fun loadWrongRecords(): List<WrongRecord> {
        val saved = preferences.getString("wrong_records", "[]") ?: "[]"
        val array = JSONArray(saved)

        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val history = item.getJSONArray("attempts")

            WrongRecord(
                questionId = item.getString("questionId"),
                attempts = List(history.length()) { historyIndex ->
                    val attempt = history.getJSONObject(historyIndex)
                    WrongAttempt(
                        answer = attempt.getString("answer"),
                        timestamp = attempt.getLong("timestamp")
                    )
                },
                resolved = item.optBoolean("resolved", false)
            )
        }
    }

    fun recordWrong(questionId: String, answer: String) {
        val records = loadWrongRecords().toMutableList()
        val index = records.indexOfFirst {
            it.questionId == questionId
        }

        val attempt = WrongAttempt(
            answer = answer,
            timestamp = System.currentTimeMillis()
        )

        if (index >= 0) {
            val previous = records[index]
            records[index] = previous.copy(
                attempts = previous.attempts + attempt,
                resolved = false
            )
        } else {
            records.add(
                WrongRecord(
                    questionId = questionId,
                    attempts = listOf(attempt),
                    resolved = false
                )
            )
        }

        saveWrongRecords(records)
    }

    fun markResolved(questionId: String) {
        val records = loadWrongRecords().map { record ->
            if (record.questionId == questionId) {
                record.copy(resolved = true)
            } else {
                record
            }
        }

        saveWrongRecords(records)
    }

    private fun saveWrongRecords(records: List<WrongRecord>) {
        val array = JSONArray()

        records.forEach { record ->
            val history = JSONArray()

            record.attempts.forEach { attempt ->
                history.put(
                    JSONObject().apply {
                        put("answer", attempt.answer)
                        put("timestamp", attempt.timestamp)
                    }
                )
            }

            array.put(
                JSONObject().apply {
                    put("questionId", record.questionId)
                    put("attempts", history)
                    put("resolved", record.resolved)
                }
            )
        }

        preferences.edit()
            .putString("wrong_records", array.toString())
            .apply()
    }
}