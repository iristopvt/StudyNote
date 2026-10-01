package com.example.studynote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.studynote.ui.theme.StudyNoteTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudyQuestion(
    val id: String,
    val question: String,
    val answers: List<String>,
    val explanation: String,
    val setName: String = "샘플 문제",
    val number: Int = 0
)

private val sampleQuestions = listOf(
    StudyQuestion(
        id = "sample_sql_distinct",
        question = "SQL에서 조회 결과의 중복 행을 제거하는 키워드는?",
        answers = listOf("DISTINCT"),
        explanation = "SELECT DISTINCT를 사용하면 중복된 행을 제거합니다."
    ),
    StudyQuestion(
        id = "sample_stack",
        question = "가장 나중에 넣은 데이터를 먼저 꺼내는 자료구조는?",
        answers = listOf("스택", "STACK"),
        explanation = "스택은 LIFO(Last In, First Out) 방식으로 동작합니다."
    ),
    StudyQuestion(
        id = "sample_c_sum",
        question = """
            다음 C 코드의 출력 결과를 입력하세요.

            int sum = 0;
            for (int i = 1; i <= 3; i++) {
                sum += i;
            }
            printf("%d", sum);
        """.trimIndent(),
        answers = listOf("6"),
        explanation = "1 + 2 + 3을 계산하므로 결과는 6입니다."
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StudyNoteTheme {
                StudyApp()
            }
        }
    }
}

private fun normalizeAnswer(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

@Composable
fun StudyApp() {
    val context = LocalContext.current
    val storage = remember(context) { StudyStorage(context) }
    val repository = remember(context) { QuestionRepository(context) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var questions by remember { mutableStateOf(sampleQuestions + repository.loadImportedQuestions()) }
    var records by remember { mutableStateOf(storage.loadWrongRecords()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedSets by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    // 문제 ID 순서를 저장해서 화면 회전이나 탭 이동에도 출제 순서를 유지합니다.
    var sessionIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var sessionAnswers by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var gradedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableStateOf(0) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewAnswer by rememberSaveable { mutableStateOf("") }
    var reviewSubmitted by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by rememberSaveable { mutableStateOf("") }
    var importSetName by rememberSaveable { mutableStateOf("") }
    val groups = questions.groupBy { it.setName }
    val byId = questions.associateBy { it.id }
    val session = sessionIds.mapNotNull { byId[it] }

    fun endSession() {
        sessionIds = arrayListOf()
        sessionAnswers = arrayListOf()
        gradedIds = arrayListOf()
        index = 0
        focus.clearFocus()
    }

    fun startSession(pool: List<StudyQuestion>, title: String, random: Boolean) {
        val chosen = if (random) pool.distinctBy { it.id }.shuffled().take(20)
        else pool.sortedBy { it.number }
        sessionIds = ArrayList(chosen.map { it.id })
        sessionAnswers = ArrayList(List(chosen.size) { "" })
        gradedIds = arrayListOf()
        sessionTitle = title
        index = 0
        focus.clearFocus()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            val nameForImport = importSetName.trim()
            importing = true
            importMessage = "문제를 가져오는 중입니다."
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val json = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader(Charsets.UTF_8).use { reader ->
                                val text = StringBuilder()
                                val buffer = CharArray(4096)
                                while (true) {
                                    val count = reader.read(buffer)
                                    if (count == -1) break
                                    text.append(buffer, 0, count)
                                    require(text.length <= 2_000_000) {
                                        "문제 파일이 너무 큽니다. 나눠서 가져와주세요."
                                    }
                                }
                                text.toString()
                            }
                        } ?: error("파일을 열 수 없습니다.")
                        repository.importQuestions(json, sampleQuestions.map { it.id }.toSet(), nameForImport)
                    }
                    questions = withContext(Dispatchers.IO) {
                        sampleQuestions + repository.loadImportedQuestions()
                    }
                    importMessage = "${result.added}개 추가 · 중복 ${result.skipped}개 건너뜀"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    importMessage = "가져오기 실패: ${e.message ?: "파일을 확인해주세요."}"
                } finally {
                    importing = false
                }
            }
        }
    }

    val isReview = tab == 1
    val current = when (tab) {
        0 -> session.getOrNull(index)
        1 -> byId[reviewId]
        else -> null
    }
    val answer = if (isReview) reviewAnswer else sessionAnswers.getOrNull(index) ?: ""
    val submitted = if (isReview) reviewSubmitted else current != null && current.id in gradedIds
    val correct = current?.answers?.any { normalizeAnswer(it) == normalizeAnswer(answer) } == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                listOf("문제 풀기", "오답노트", "자료 관리").forEachIndexed { number, title ->
                    NavigationBarItem(
                        selected = tab == number,
                        onClick = { tab = number; focus.clearFocus() },
                        icon = { Text(listOf("문제", "오답", "자료")[number]) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("StudyNote", style = MaterialTheme.typography.headlineMedium)
            when {
                tab == 2 -> {
                    Text("자료 관리", style = MaterialTheme.typography.titleLarge)
                    Text("전체 ${questions.size}문제 · ${groups.size}개 묶음")
                    groups.forEach { (name, items) -> Text("$name: ${items.size}문제") }
                    OutlinedTextField(
                        value = importSetName,
                        onValueChange = { importSetName = it },
                        label = { Text("회차 이름") },
                        placeholder = { Text("예: 2024년 2회차") },
                        singleLine = true,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("회차 이름을 입력하고 JSON 파일을 선택하세요. 같은 ID는 건너뜁니다.")
                    Button(
                        onClick = { focus.clearFocus(); importLauncher.launch(arrayOf("*/*")) },
                        enabled = !importing && importSetName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (importing) "가져오는 중…" else "문제 파일 가져오기") }
                    if (importMessage.isNotBlank()) Text(importMessage)
                }
                current != null -> {
                    val question = current
                    Text(if (isReview) "오답 다시 풀기" else "$sessionTitle · ${index + 1}/${session.size}")
                    Text(question.setName + if (question.number > 0) " · ${question.number}번" else "")
                    Card(Modifier.fillMaxWidth()) {
                        Text(question.question, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { value ->
                            if (isReview) reviewAnswer = value
                            else sessionAnswers = ArrayList(sessionAnswers).also { it[index] = value }
                        },
                        label = { Text("답을 입력하세요") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitted,
                        minLines = 2
                    )
                    if (!submitted) {
                        Button(
                            onClick = {
                                focus.clearFocus()
                                if (correct) storage.markResolved(question.id)
                                else storage.recordWrong(question.id, answer)
                                records = storage.loadWrongRecords()
                                if (isReview) reviewSubmitted = true
                                else gradedIds = ArrayList(gradedIds + question.id)
                            },
                            enabled = answer.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("제출") }
                    } else {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (correct)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (correct) "정답입니다!" else "틀렸습니다. 오답노트에 저장했어요.",
                                    style = MaterialTheme.typography.titleLarge)
                                Text("내 답: $answer")
                                Text("정답: ${question.answers.first()}")
                                Text("해설: ${question.explanation}")
                                if (isReview && correct) Text("복습 완료! 이전 오답 이력은 유지됩니다.")
                            }
                        }
                        if (!isReview && index == session.lastIndex) {
                            val score = session.indices.count { i ->
                                session[i].id in gradedIds && session[i].answers.any {
                                    normalizeAnswer(it) == normalizeAnswer(sessionAnswers[i])
                                }
                            }
                            Text("완료! ${session.size}문제 중 ${score}문제 정답")
                        }
                        Button(
                            onClick = {
                                focus.clearFocus()
                                if (isReview) { reviewId = null; reviewAnswer = ""; reviewSubmitted = false }
                                else if (index == session.lastIndex) endSession()
                                else index++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (isReview) "오답 목록으로" else if (index == session.lastIndex) "회차 선택으로" else "다음 문제") }
                    }
                    TextButton(onClick = {
                        if (isReview) { reviewId = null; reviewAnswer = ""; reviewSubmitted = false }
                        else endSession()
                    }) { Text(if (isReview) "오답 목록으로 돌아가기" else "풀이 종료 · 회차 선택으로") }
                }
                tab == 0 -> {
                    Text("회차 선택", style = MaterialTheme.typography.titleLarge)
                    groups.forEach { (name, items) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text("${items.size}문제")
                                Button(onClick = { startSession(items, name, false) }) { Text("이 회차 풀기") }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("랜덤 20문제", style = MaterialTheme.typography.titleLarge)
                    Text("출제할 회차를 선택하세요. 20문제보다 적으면 있는 문제만 출제합니다.")
                    groups.filterKeys { it != "샘플 문제" }.forEach { (name, items) ->
                        Row {
                            Checkbox(
                                checked = name in selectedSets,
                                onCheckedChange = { checked ->
                                    selectedSets = ArrayList(if (checked) (selectedSets + name).distinct()
                                    else selectedSets.filterNot { it == name })
                                }
                            )
                            Text("$name (${items.size}문제)", Modifier.padding(top = 12.dp))
                        }
                    }
                    val pool = questions.filter { it.setName in selectedSets && it.setName != "샘플 문제" }
                    Text("선택한 문제 ${pool.size}개 · 출제 ${minOf(20, pool.size)}개")
                    Button(
                        onClick = { startSession(pool, "랜덤 복습", true) },
                        enabled = pool.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("랜덤 풀기 시작") }
                }
                else -> {
                    Text("오답노트", style = MaterialTheme.typography.titleLarge)
                    Text("복습 필요 ${records.count { !it.resolved }}개 · 복습 완료 ${records.count { it.resolved }}개")
                    if (records.isEmpty()) Text("아직 저장된 오답이 없어요.")
                    records.sortedBy { it.resolved }.forEach { record ->
                        byId[record.questionId]?.let { question ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (record.resolved) "복습 완료" else "복습 필요")
                                    Text(question.setName + if (question.number > 0) " · ${question.number}번" else "")
                                    Text(question.question)
                                    Text("틀린 횟수: ${record.attempts.size}회")
                                    Text("최근 틀린 답: ${record.attempts.lastOrNull()?.answer ?: ""}")
                                    Button(onClick = {
                                        reviewId = question.id; reviewAnswer = ""; reviewSubmitted = false
                                        focus.clearFocus()
                                    }) { Text("다시 풀기") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
