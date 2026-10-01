package com.example.studynote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(val added: Int, val skipped: Int)

class QuestionRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("study_questions", Context.MODE_PRIVATE)

    fun loadImportedQuestions(): List<StudyQuestion> =
        parseQuestions(preferences.getString("questions", "[]") ?: "[]")

    fun importQuestions(
        json: String,
        existingIds: Set<String>,
        defaultSetName: String = "가져온 문제"
    ): ImportResult {
        val incoming = parseQuestions(json, defaultSetName.trim())
        require(incoming.isNotEmpty()) { "문제 파일이 비어 있습니다." }
        val saved = loadImportedQuestions()
        val knownIds = (existingIds + saved.map { it.id }).toMutableSet()
        val additions = incoming.filter { knownIds.add(it.id) }
        if (additions.isNotEmpty()) saveQuestions(saved + additions)
        return ImportResult(additions.size, incoming.size - additions.size)
    }

    private fun parseQuestions(
        json: String,
        defaultSetName: String = "가져온 문제"
    ): List<StudyQuestion> {
        val array = JSONArray(json.trim().removePrefix("\uFEFF"))
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id").trim()
            val question = item.getString("question")
            val explanation = item.getString("explanation")
            val answerArray = item.getJSONArray("answers")
            require(id.isNotBlank()) { "${index + 1}번 문제의 ID가 비어 있습니다." }
            require(question.isNotBlank()) { "${index + 1}번 문제의 내용이 비어 있습니다." }
            require(explanation.isNotBlank()) { "${index + 1}번 문제의 해설이 비어 있습니다." }
            require(answerArray.length() > 0) { "${index + 1}번 문제에 정답이 없습니다." }
            val answers = List(answerArray.length()) { answerIndex ->
                answerArray.getString(answerIndex).also {
                    require(it.isNotBlank()) { "${index + 1}번 문제에 빈 정답이 있습니다." }
                }
            }
            // 이전 JSON과 저장된 문제도 같은 ID로 그대로 읽습니다.
            val legacy = Regex("^ip_(\\d{4})_(\\d+)_q(\\d+)$").matchEntire(id)
            val heading = Regex("^\\[(\\d{4})년 (\\d+)회차 · (\\d+)번\\]").find(question)
            val inferredName = legacy?.let {
                "${it.groupValues[1]}년 ${it.groupValues[2]}회차"
            } ?: heading?.let {
                "${it.groupValues[1]}년 ${it.groupValues[2]}회차"
            }
            val setName = item.optString("setName", "").trim().ifBlank {
                // 가져오기 화면의 이름을 우선하고, 기존 저장 자료는 ID로 복원합니다.
                if (defaultSetName != "가져온 문제" && defaultSetName.isNotBlank()) {
                    defaultSetName
                } else inferredName ?: "가져온 문제"
            }
            val inferredNumber = legacy?.groupValues?.get(3)?.toIntOrNull()
                ?: heading?.groupValues?.get(3)?.toIntOrNull() ?: index + 1
            StudyQuestion(
                id, question, answers, explanation, setName,
                item.optInt("number", inferredNumber).takeIf { it > 0 } ?: inferredNumber
            )
        }
    }

    private fun saveQuestions(questions: List<StudyQuestion>) {
        val array = JSONArray()
        questions.forEach { question ->
            val answers = JSONArray()
            question.answers.forEach { answers.put(it) }
            array.put(JSONObject().apply {
                put("id", question.id)
                put("question", question.question)
                put("answers", answers)
                put("explanation", question.explanation)
                put("setName", question.setName)
                put("number", question.number)
            })
        }
        check(preferences.edit().putString("questions", array.toString()).commit()) {
            "문제를 저장하지 못했습니다. 다시 시도해주세요."
        }
    }
}
