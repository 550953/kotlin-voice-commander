package com.example.voskspeechdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class MainActivity : ComponentActivity(), RecognitionListener, TextToSpeech.OnInitListener {

    private lateinit var database: DictionaryDatabaseHelper

    private var uiState by mutableStateOf(AppUiState())
    private var currentScreen by mutableStateOf(AppScreen.Home)
    private var phoneticEditor by mutableStateOf<PhoneticEditorState?>(null)
    private var mainEditor by mutableStateOf<MainEditorState?>(null)

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var textToSpeech: TextToSpeech? = null
    private var phoneticDictionary = PhoneticDictionary()
    private var mainDictionary: Map<String, String> = emptyMap()
    private var maxMainAbbreviationLength = 0
    private var recognizedInCurrentSession = false
    private var hasFinalizedRecognitionSession = false
    private var stableRecognizedText = ""
    private var currentPartialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = DictionaryDatabaseHelper(this)
        textToSpeech = TextToSpeech(this, this)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FB")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        setContent {
            AbbreviationVoiceTheme {
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        startListening()
                    } else {
                        updateError("Для распознавания нужен доступ к микрофону.")
                    }
                }

                val phoneticImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) replacePhoneticDictionaryFromJson(uri)
                }

                val mainImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) replaceMainDictionaryFromJson(uri)
                }

                LaunchedEffect(Unit) {
                    if (!hasAudioPermission()) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        state = uiState,
                        currentScreen = currentScreen,
                        onScreenSelected = { currentScreen = it },
                        onToggleListening = {
                            if (uiState.isListening) {
                                stopListening()
                            } else if (hasAudioPermission()) {
                                startListening()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onAddPhonetic = { phoneticEditor = PhoneticEditorState() },
                        onEditPhonetic = { letter, variants ->
                            phoneticEditor = PhoneticEditorState(letter = letter, variantsText = variants.joinToString(", "))
                        },
                        onDeletePhonetic = { letter ->
                            database.deletePhoneticEntry(letter)
                            refreshDictionariesFromDatabase()
                        },
                        onImportPhonetic = {
                            phoneticImportLauncher.launch(arrayOf("application/json", "text/*"))
                        },
                        onAddMain = { mainEditor = MainEditorState() },
                        onEditMain = { abbreviation, value ->
                            mainEditor = MainEditorState(abbreviation = abbreviation, value = value)
                        },
                        onDeleteMain = { abbreviation ->
                            database.deleteMainEntry(abbreviation)
                            refreshDictionariesFromDatabase()
                        },
                        onImportMain = {
                            mainImportLauncher.launch(arrayOf("application/json", "text/*"))
                        },
                    )

                    phoneticEditor?.let { editor ->
                        PhoneticEditorDialog(
                            state = editor,
                            onDismiss = { phoneticEditor = null },
                            onSave = { letterText, variantsText ->
                                savePhoneticEntry(letterText, variantsText)
                            },
                        )
                    }

                    mainEditor?.let { editor ->
                        MainEditorDialog(
                            state = editor,
                            onDismiss = { mainEditor = null },
                            onSave = { abbreviationText, valueText ->
                                saveMainEntry(abbreviationText, valueText)
                            },
                        )
                    }
                }
            }
        }

        seedDatabaseIfNeeded()
        refreshDictionariesFromDatabase()
        initModel()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateError("Не удалось инициализировать синтез речи.")
            return
        }
        val localeStatus = textToSpeech?.setLanguage(RUSSIAN_LOCALE) ?: TextToSpeech.ERROR
        uiState = if (localeStatus == TextToSpeech.LANG_MISSING_DATA || localeStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            uiState.copy(ttsReady = false, errorMessage = "На устройстве недоступен русский голос для TextToSpeech.")
        } else {
            uiState.copy(ttsReady = true)
        }
    }

    private fun initModel() {
        uiState = uiState.copy(
            modelStatus = LoadStatus.Loading("Модель Vosk загружается"),
            errorMessage = "",
        )
        resetBrokenModelCacheIfNeeded()
        StorageService.unpack(
            this,
            "model",
            UNPACKED_MODEL_DIR,
            { unpackedModel ->
                runOnUiThread {
                    model = unpackedModel
                    rebuildRecognizer()
                    uiState = uiState.copy(modelStatus = LoadStatus.Ready("Модель Vosk готова"))
                }
            },
            { error ->
                runOnUiThread {
                    uiState = uiState.copy(
                        modelStatus = LoadStatus.Error("Ошибка загрузки Vosk"),
                        errorMessage = "Vosk model is missing. Put a model into app/src/main/assets/model.\n${error.localizedMessage ?: error}",
                    )
                }
            },
        )
    }

    private fun seedDatabaseIfNeeded() {
        if (database.getPhoneticDictionary().isNotEmpty() || database.getMainDictionary().isNotEmpty()) return

        val phoneticJson = assets.open(DEFAULT_PHONETIC_FILE).bufferedReader().use { it.readText() }
        val defaultPhonetic = parsePhoneticDictionary(phoneticJson)
        database.replacePhoneticDictionary(defaultPhonetic.letterToVariants)

        val mainJson = assets.open(DEFAULT_MAIN_DICTIONARY_FILE).bufferedReader().use { it.readText() }
        val defaultMain = parseMainDictionary(mainJson)
        database.replaceMainDictionary(defaultMain)
    }

    private fun refreshDictionariesFromDatabase() {
        val phoneticMap = database.getPhoneticDictionary()
        val mainMap = database.getMainDictionary()

        phoneticDictionary = PhoneticDictionary(
            letterToVariants = phoneticMap,
            pronunciationToLetter = buildPronunciationToLetterMap(phoneticMap),
        )
        mainDictionary = mainMap
        maxMainAbbreviationLength = mainMap.keys.maxOfOrNull { it.length } ?: 0
        rebuildRecognizer()

        uiState = uiState.copy(
            phoneticStatus = if (phoneticMap.isEmpty()) LoadStatus.NotLoaded else LoadStatus.Ready("Записей: ${phoneticMap.size}"),
            mainDictionaryStatus = if (mainMap.isEmpty()) LoadStatus.NotLoaded else LoadStatus.Ready("Записей: ${mainMap.size}"),
            phoneticEntries = phoneticMap.toList(),
            mainDictionaryEntries = mainMap.toList(),
        )
    }

    private fun replacePhoneticDictionaryFromJson(uri: Uri) {
        try {
            persistReadPermission(uri)
            val parsed = parsePhoneticDictionary(readTextFromUri(uri))
            database.replacePhoneticDictionary(parsed.letterToVariants)
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(errorMessage = "")
        } catch (error: Exception) {
            updateError("Некорректный фонетический JSON: ${error.localizedMessage ?: error}")
        }
    }

    private fun replaceMainDictionaryFromJson(uri: Uri) {
        try {
            persistReadPermission(uri)
            val parsed = parseMainDictionary(readTextFromUri(uri))
            database.replaceMainDictionary(parsed)
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(errorMessage = "")
        } catch (error: Exception) {
            updateError("Некорректный основной JSON: ${error.localizedMessage ?: error}")
        }
    }

    private fun savePhoneticEntry(letterText: String, variantsText: String) {
        try {
            val letter = normalizeLetter(letterText)
            val variants = parseVariants(variantsText)
            if (letter.isBlank() || variants.isEmpty()) {
                throw IllegalArgumentException("Укажите букву и хотя бы одно произношение.")
            }
            database.upsertPhoneticEntry(letter, variants)
            phoneticEditor = null
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(errorMessage = "")
        } catch (error: Exception) {
            updateError(error.localizedMessage ?: error.toString())
        }
    }

    private fun saveMainEntry(abbreviationText: String, valueText: String) {
        try {
            val abbreviation = normalizeAbbreviationKey(abbreviationText)
            val value = valueText.trim()
            if (abbreviation.isBlank() || value.isBlank()) {
                throw IllegalArgumentException("Укажите аббревиатуру и значение.")
            }
            database.upsertMainEntry(abbreviation, value)
            mainEditor = null
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(errorMessage = "")
        } catch (error: Exception) {
            updateError(error.localizedMessage ?: error.toString())
        }
    }

    private fun parseVariants(value: String): List<String> =
        value.split(",")
            .map { normalizeSpeech(it) }
            .filter { it.isNotBlank() }
            .distinct()

    private fun normalizeLetter(value: String): String =
        value.trim().uppercase(RUSSIAN_LOCALE)

    private fun buildPronunciationToLetterMap(entries: Map<String, List<String>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        entries.forEach { (letter, variants) ->
            variants.forEach { variant ->
                result.putIfAbsent(normalizeSpeech(variant), letter)
            }
        }
        return result
    }

    private fun startListening() {
        rebuildRecognizer()
        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            updateError("Модель Vosk ещё не готова.")
            return
        }
        if (phoneticDictionary.pronunciationToLetter.isEmpty()) {
            updateError("Фонетический словарь пуст.")
            return
        }
        if (mainDictionary.isEmpty()) {
            updateError("Основной словарь пуст.")
            return
        }
        if (speechService != null) return

        recognizedInCurrentSession = false
        hasFinalizedRecognitionSession = false
        stableRecognizedText = ""
        currentPartialText = ""
        uiState = uiState.copy(
            isListening = true,
            recognizedText = "",
            abbreviation = "",
            result = "",
            errorMessage = "",
        )
        speechService = SpeechService(currentRecognizer, SAMPLE_RATE).also {
            it.startListening(this)
        }
    }

    private fun stopListening(finalize: Boolean = true) {
        if (speechService == null && !uiState.isListening) return
        if (finalize) {
            finalizeRecognitionSession()
        }
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        currentPartialText = ""
        uiState = uiState.copy(isListening = false)
    }

    private fun rebuildRecognizer() {
        val currentModel = model ?: return
        recognizer?.close()
        recognizer = if (buildRecognitionGrammar() != null) {
            Recognizer(currentModel, SAMPLE_RATE, buildRecognitionGrammar())
        } else {
            Recognizer(currentModel, SAMPLE_RATE)
        }
    }

    private fun buildRecognitionGrammar(): String? {
        val tokenVariants = phoneticDictionary.pronunciationToLetter.keys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (tokenVariants.isEmpty()) return null

        val grammarPhrases = linkedSetOf<String>()
        grammarPhrases += tokenVariants

        mainDictionary.keys
            .map { normalizeAbbreviationKey(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { abbreviation ->
                grammarPhrases += generatePronunciationPhrases(abbreviation)
            }

        val jsonArray = JSONArray()
        grammarPhrases.take(MAX_GRAMMAR_PHRASES).forEach { jsonArray.put(it) }
        jsonArray.put("[unk]")
        return jsonArray.toString()
    }

    private fun generatePronunciationPhrases(abbreviation: String): Set<String> {
        val perLetterVariants = abbreviation.map { letter ->
            phoneticDictionary.letterToVariants[letter.toString()]
                ?.map { normalizeSpeech(it) }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
        }
        if (perLetterVariants.any { it.isEmpty() }) return emptySet()

        val phrases = linkedSetOf<String>()
        fun build(index: Int, current: List<String>) {
            if (phrases.size >= MAX_VARIANT_PHRASES_PER_ABBREVIATION) return
            if (index == perLetterVariants.size) {
                phrases += current.joinToString(" ")
                phrases += current.joinToString("")
                return
            }
            for (variant in perLetterVariants[index]) {
                build(index + 1, current + variant)
            }
        }
        build(0, emptyList())
        return phrases
    }

    override fun onPartialResult(hypothesis: String?) {
        processRecognition(hypothesis, mode = RecognitionMode.Partial)
    }

    override fun onResult(hypothesis: String?) {
        processRecognition(hypothesis, mode = RecognitionMode.StableChunk)
    }

    override fun onFinalResult(hypothesis: String?) {
        processRecognition(hypothesis, mode = RecognitionMode.FinalChunk)
        stopListening()
    }

    override fun onError(exception: Exception?) {
        runOnUiThread {
            updateError(exception?.localizedMessage ?: exception.toString())
            stopListening(finalize = false)
        }
    }

    override fun onTimeout() {
        runOnUiThread {
            updateError("Распознавание остановлено по таймауту.")
            stopListening()
        }
    }

    private fun processRecognition(hypothesis: String?, mode: RecognitionMode) {
        if (hasFinalizedRecognitionSession && mode != RecognitionMode.Partial) return

        val rawText = extractRecognizedText(hypothesis)
        if (rawText.isBlank()) return

        when (mode) {
            RecognitionMode.Partial -> currentPartialText = rawText
            RecognitionMode.StableChunk -> {
                stableRecognizedText = mergeRecognizedText(stableRecognizedText, rawText)
                currentPartialText = ""
            }
            RecognitionMode.FinalChunk -> {
                stableRecognizedText = mergeRecognizedText(stableRecognizedText, rawText)
                currentPartialText = ""
            }
        }

        val combinedText = mergeRecognizedText(stableRecognizedText, currentPartialText)
        val abbreviation = decodeAbbreviation(combinedText, phoneticDictionary.pronunciationToLetter).orEmpty()
        val shouldAutoStop = shouldAutoStopRecognition(abbreviation)
        runOnUiThread {
            uiState = uiState.copy(
                recognizedText = combinedText,
                abbreviation = abbreviation,
                result = if (mode == RecognitionMode.Partial) uiState.result else "",
                errorMessage = if (mode == RecognitionMode.Partial) uiState.errorMessage else "",
            )

            if (shouldAutoStop && uiState.isListening) {
                stopListening()
            }
        }
    }

    private fun finalizeRecognitionSession() {
        if (hasFinalizedRecognitionSession) return
        val combinedText = mergeRecognizedText(stableRecognizedText, currentPartialText)
        if (combinedText.isBlank()) {
            hasFinalizedRecognitionSession = true
            return
        }

        val abbreviation = decodeAbbreviation(combinedText, phoneticDictionary.pronunciationToLetter).orEmpty()
        val nextResult = resolveResult(abbreviation)
        hasFinalizedRecognitionSession = true
        uiState = uiState.copy(
            recognizedText = combinedText,
            abbreviation = abbreviation,
            result = nextResult.result,
            errorMessage = nextResult.errorMessage.orEmpty(),
        )
        if (nextResult.result.isNotBlank()) {
            recognizedInCurrentSession = true
            speakResult(nextResult.result)
        }
    }

    private fun mergeRecognizedText(existing: String, incoming: String): String {
        val left = existing.trim()
        val right = incoming.trim()

        if (left.isBlank()) return right
        if (right.isBlank()) return left
        if (left == right) return left
        if (right.startsWith(left)) return right
        if (left.endsWith(right)) return left

        return "$left $right".trim()
    }

    private fun shouldAutoStopRecognition(abbreviation: String): Boolean {
        if (abbreviation.isBlank()) return false
        if (mainDictionary.containsKey(abbreviation)) return true
        if (maxMainAbbreviationLength <= 0) return false
        return abbreviation.length > maxMainAbbreviationLength
    }

    private fun resolveResult(abbreviation: String): ResolutionResult {
        if (abbreviation.isBlank()) {
            return ResolutionResult("", "Не удалось преобразовать распознанный текст в аббревиатуру.")
        }
        val result = mainDictionary[abbreviation]
        return if (result != null) ResolutionResult(result, null) else ResolutionResult("", "Аббревиатура не найдена: $abbreviation")
    }

    private fun speakResult(value: String) {
        if (!uiState.ttsReady) return
        val spokenText = value.map { digitToSpeech(it) }.joinToString(" ")
        textToSpeech?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "decoded-command")
    }

    private fun digitToSpeech(char: Char): String = when (char) {
        '0' -> "ноль"
        '1' -> "один"
        '2' -> "два"
        '3' -> "три"
        '4' -> "четыре"
        '5' -> "пять"
        '6' -> "шесть"
        '7' -> "семь"
        '8' -> "восемь"
        '9' -> "девять"
        else -> char.toString()
    }

    private fun decodeAbbreviation(recognizedText: String, pronunciationToLetter: Map<String, String>): String? {
        if (pronunciationToLetter.isEmpty()) return null
        val normalizedText = normalizeSpeech(recognizedText)
        if (normalizedText.isBlank()) return null

        val tokens = normalizedText.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isNotEmpty()) {
            val tokenBased = tokens.map { pronunciationToLetter[it] }
            if (tokenBased.all { it != null }) {
                return tokenBased.joinToString("") { it.orEmpty() }
            }
        }
        return splitPhoneticChain(normalizedText.replace(" ", ""), pronunciationToLetter)
    }

    private fun splitPhoneticChain(text: String, pronunciationToLetter: Map<String, String>): String? {
        val variants = pronunciationToLetter.keys.sortedByDescending { it.length }
        val memo = HashMap<Int, String?>()

        fun dfs(index: Int): String? {
            if (memo.containsKey(index)) return memo[index]
            if (index == text.length) return ""
            for (variant in variants) {
                if (!text.startsWith(variant, index)) continue
                val tail = dfs(index + variant.length) ?: continue
                val decoded = pronunciationToLetter.getValue(variant) + tail
                memo[index] = decoded
                return decoded
            }
            memo[index] = null
            return null
        }
        return dfs(0)
    }

    private fun parsePhoneticDictionary(jsonText: String): PhoneticDictionary {
        val root = JSONObject(jsonText)
        if (root.length() == 0) throw IllegalArgumentException("Файл пустой.")
        val letterToVariants = linkedMapOf<String, List<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val letter = normalizeLetter(key)
            val value = root.get(key)
            if (value !is JSONArray || value.length() == 0) {
                throw IllegalArgumentException("Для буквы $letter нужен непустой массив произношений.")
            }
            val variants = buildList {
                for (index in 0 until value.length()) {
                    val variant = normalizeSpeech(value.optString(index))
                    if (variant.isBlank()) throw IllegalArgumentException("Пустое произношение для буквы $letter.")
                    add(variant)
                }
            }.distinct()
            letterToVariants[letter] = variants
        }
        return PhoneticDictionary(letterToVariants, buildPronunciationToLetterMap(letterToVariants))
    }

    private fun parseMainDictionary(jsonText: String): Map<String, String> {
        val root = JSONObject(jsonText)
        if (root.length() == 0) throw IllegalArgumentException("Файл пустой.")
        val result = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val abbreviation = normalizeAbbreviationKey(key)
            val value = root.get(key).toString().trim()
            if (abbreviation.isBlank() || value.isBlank()) throw IllegalArgumentException("Пустые данные в основном словаре.")
            result[abbreviation] = value
        }
        return result
    }

    private fun extractRecognizedText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching {
            val json = JSONObject(hypothesis)
            json.optString("text").ifBlank { json.optString("partial") }
        }.getOrDefault(hypothesis).trim()
    }

    private fun normalizeSpeech(value: String): String =
        value.lowercase(RUSSIAN_LOCALE)
            .replace('ё', 'е')
            .replace(NON_LETTER_OR_DIGIT_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun normalizeAbbreviationKey(value: String): String =
        value.uppercase(RUSSIAN_LOCALE).replace(WHITESPACE_REGEX, "")

    private fun updateError(message: String) {
        uiState = uiState.copy(errorMessage = message)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun readTextFromUri(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Не удалось открыть файл.")

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun queryFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment ?: "json"
    }

    private fun resetBrokenModelCacheIfNeeded() {
        val cachedModelDir = File(filesDir, UNPACKED_MODEL_DIR)
        if (cachedModelDir.exists() && !hasRequiredModelFiles(cachedModelDir)) {
            cachedModelDir.deleteRecursively()
        }
    }

    private fun hasRequiredModelFiles(modelDir: File): Boolean {
        val requiredPaths = listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/HCLr.fst",
            "graph/Gr.fst",
            "graph/phones/word_boundary.int",
            "uuid",
        )
        return requiredPaths.all { File(modelDir, it).isFile }
    }

    override fun onDestroy() {
        speechService?.shutdown()
        recognizer?.close()
        model?.close()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000.0f
        private const val UNPACKED_MODEL_DIR = "vosk-model-cache-v3"
        private const val DEFAULT_PHONETIC_FILE = "default_phonetic_dictionary.json"
        private const val DEFAULT_MAIN_DICTIONARY_FILE = "default_main_dictionary.json"
        private const val MAX_GRAMMAR_PHRASES = 5000
        private const val MAX_VARIANT_PHRASES_PER_ABBREVIATION = 256
        private val RUSSIAN_LOCALE: Locale = Locale.forLanguageTag("ru-RU")
        private val NON_LETTER_OR_DIGIT_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

private enum class AppScreen(val title: String) {
    Home("Главный"),
    Phonetic("Фонетический"),
    Main("Основной"),
}

private data class AppUiState(
    val modelStatus: LoadStatus = LoadStatus.Loading("Модель Vosk загружается"),
    val phoneticStatus: LoadStatus = LoadStatus.NotLoaded,
    val mainDictionaryStatus: LoadStatus = LoadStatus.NotLoaded,
    val phoneticEntries: List<Pair<String, List<String>>> = emptyList(),
    val mainDictionaryEntries: List<Pair<String, String>> = emptyList(),
    val recognizedText: String = "",
    val abbreviation: String = "",
    val result: String = "",
    val errorMessage: String = "",
    val isListening: Boolean = false,
    val ttsReady: Boolean = false,
)

private data class PhoneticDictionary(
    val letterToVariants: Map<String, List<String>> = emptyMap(),
    val pronunciationToLetter: Map<String, String> = emptyMap(),
)

private data class ResolutionResult(val result: String, val errorMessage: String?)
private data class PhoneticEditorState(val letter: String = "", val variantsText: String = "")
private data class MainEditorState(val abbreviation: String = "", val value: String = "")

private enum class RecognitionMode {
    Partial,
    StableChunk,
    FinalChunk,
}

private sealed interface LoadStatus {
    val message: String
    data object NotLoaded : LoadStatus { override val message = "Файл не загружен" }
    data class Loading(override val message: String) : LoadStatus
    data class Ready(override val message: String) : LoadStatus
    data class Error(override val message: String) : LoadStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    state: AppUiState,
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
    onToggleListening: () -> Unit,
    onAddPhonetic: () -> Unit,
    onEditPhonetic: (String, List<String>) -> Unit,
    onDeletePhonetic: (String) -> Unit,
    onImportPhonetic: () -> Unit,
    onAddMain: () -> Unit,
    onEditMain: (String, String) -> Unit,
    onDeleteMain: (String) -> Unit,
    onImportMain: () -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFFFFFFFF),
                tonalElevation = 8.dp,
            ) {
                AppScreen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen == screen,
                        onClick = { onScreenSelected(screen) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF6750A4),
                            selectedTextColor = Color(0xFF6750A4),
                            indicatorColor = Color(0xFFE8DEF8),
                            unselectedIconColor = Color(0xFF6B7280),
                            unselectedTextColor = Color(0xFF6B7280),
                        ),
                        icon = {
                            Icon(
                                imageVector = when (screen) {
                                    AppScreen.Home -> Icons.Filled.Home
                                    AppScreen.Phonetic -> Icons.Filled.RecordVoiceOver
                                    AppScreen.Main -> Icons.Filled.ShortText
                                },
                                contentDescription = screen.title,
                            )
                        },
                        label = { Text(screen.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (currentScreen) {
            AppScreen.Home -> HomeScreen(state, innerPadding, onToggleListening)
            AppScreen.Phonetic -> PhoneticScreen(state, innerPadding, onAddPhonetic, onEditPhonetic, onDeletePhonetic, onImportPhonetic)
            AppScreen.Main -> MainDictionaryScreen(state, innerPadding, onAddMain, onEditMain, onDeleteMain, onImportMain)
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, innerPadding: PaddingValues, onToggleListening: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompactStatusLine(
            title = "Vosk:",
            value = state.modelStatus.message,
            accentColor = when (state.modelStatus) {
                is LoadStatus.Ready -> Color(0xFF2E7D32)
                is LoadStatus.Error -> Color(0xFFC62828)
                is LoadStatus.Loading -> Color(0xFF1565C0)
                LoadStatus.NotLoaded -> Color(0xFF6B7280)
            },
        )

        if (state.errorMessage.isNotBlank() || !state.ttsReady) {
            CompactMessageLine(
                title = "Ошибка:",
                value = state.errorMessage.ifBlank { "TextToSpeech не готов" },
                valueColor = if (state.errorMessage.isNotBlank()) Color(0xFFB71C1C) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledIconButton(
            onClick = onToggleListening,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .aspectRatio(1f),
            enabled = state.modelStatus is LoadStatus.Ready,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = if (state.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (state.isListening) "Остановить" else "Начать слушать",
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ResultLine("Распознано:", state.recognizedText.ifBlank { "-" })
            ResultLine("Ответ:", state.abbreviation.ifBlank { "-" })
            ResultLine("Результат:", state.result.ifBlank { "-" })
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactStatusLine(
    title: String,
    value: String,
    accentColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(accentColor, RoundedCornerShape(100.dp))
                    .height(10.dp)
                    .fillMaxWidth(0.025f),
            )
            Text(
                text = "$title $value",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactMessageLine(
    title: String,
    value: String,
    valueColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = "$title $value",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun ResultLine(
    label: String,
    value: String,
) {
    Text(
        text = "$label $value",
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
    )
}

@Composable
private fun PhoneticScreen(
    state: AppUiState,
    innerPadding: PaddingValues,
    onAdd: () -> Unit,
    onEdit: (String, List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Фонетический словарь: ${state.phoneticEntries.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Добавить") }
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Заменить из JSON") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.phoneticEntries, key = { it.first }) { entry ->
                EditableDictionaryCard(
                    title = entry.first,
                    subtitle = entry.second.joinToString(", "),
                    onEdit = { onEdit(entry.first, entry.second) },
                    onDelete = { onDelete(entry.first) },
                )
            }
        }
    }
}

@Composable
private fun MainDictionaryScreen(
    state: AppUiState,
    innerPadding: PaddingValues,
    onAdd: () -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Основной словарь: ${state.mainDictionaryEntries.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Добавить") }
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Заменить из JSON") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.mainDictionaryEntries, key = { it.first }) { entry ->
                EditableDictionaryCard(
                    title = entry.first,
                    subtitle = entry.second,
                    onEdit = { onEdit(entry.first, entry.second) },
                    onDelete = { onDelete(entry.first) },
                )
            }
        }
    }
}

@Composable
private fun EditableDictionaryCard(
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Редактировать") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Удалить") }
            }
        }
    }
}

@Composable
private fun PhoneticEditorDialog(
    state: PhoneticEditorState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var letter by mutableStateOf(state.letter)
    var variantsText by mutableStateOf(state.variantsText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.letter.isBlank()) "Новая буква" else "Редактировать букву") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = letter, onValueChange = { letter = it }, label = { Text("Буква") }, singleLine = true)
                OutlinedTextField(
                    value = variantsText,
                    onValueChange = { variantsText = it },
                    label = { Text("Произношения через запятую") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(letter, variantsText) }) { Text("Сохранить") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun MainEditorDialog(
    state: MainEditorState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var abbreviation by mutableStateOf(state.abbreviation)
    var value by mutableStateOf(state.value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.abbreviation.isBlank()) "Новая команда" else "Редактировать команду") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = abbreviation, onValueChange = { abbreviation = it }, label = { Text("Аббревиатура") }, singleLine = true)
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Значение") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(abbreviation, value) }) { Text("Сохранить") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun StatusCard(title: String, status: LoadStatus) {
    val accentColor = when (status) {
        is LoadStatus.Ready -> Color(0xFF2E7D32)
        is LoadStatus.Error -> Color(0xFFC62828)
        is LoadStatus.Loading -> Color(0xFF1565C0)
        LoadStatus.NotLoaded -> Color(0xFF6B7280)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .background(accentColor, RoundedCornerShape(100.dp))
                        .height(10.dp)
                        .fillMaxWidth(0.04f),
                )
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    val hasMessage = message.isNotBlank()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (hasMessage) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (hasMessage) "Ошибка / статус" else "Статус", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (hasMessage) message else "Нет ошибок", style = MaterialTheme.typography.bodyMedium, color = if (hasMessage) Color(0xFF7F1D1D) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AbbreviationVoiceTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF6750A4),
        secondary = Color(0xFF7D5260),
        background = Color(0xFFF5F7FB),
        surface = Color.White,
        onPrimary = Color.White,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
