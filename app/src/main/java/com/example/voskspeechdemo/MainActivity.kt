package com.example.voskspeechdemo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
    private lateinit var preferences: SharedPreferences

    private var uiState by mutableStateOf(AppUiState())

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var textToSpeech: TextToSpeech? = null
    private var prefixDictionary = PrefixDictionary()
    private var mainDictionary: Map<String, String> = emptyMap()
    private var maxMainAbbreviationLength = 0
    private var stableRecognizedText = ""
    private var currentPartialText = ""
    private var hasFinalizedRecognitionSession = false
    private var shouldStartListeningAfterPermissionGrant = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognitionProgressSignature = ""
    private val recognitionTimeoutRunnable = Runnable {
        handleRecognitionInactivityTimeout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        database = DictionaryDatabaseHelper(this)
        textToSpeech = TextToSpeech(this, this)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    uiState = uiState.copy(
                        ttsStatus = TtsStatus.Speaking,
                        currentSpeakingAnswerIndex = parseSpeakingAnswerIndex(utteranceId),
                    )
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    uiState = uiState.copy(
                        ttsStatus = if (uiState.ttsReady) TtsStatus.Ready else TtsStatus.Error,
                        currentSpeakingAnswerIndex = if (isLastAnswerUtterance(utteranceId) || utteranceId == TTS_PREVIEW_UTTERANCE_ID) null else uiState.currentSpeakingAnswerIndex,
                    )
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    uiState = uiState.copy(ttsStatus = TtsStatus.Error, currentSpeakingAnswerIndex = null)
                }
            }
        })
        uiState = uiState.copy(answerRepeatCount = preferences.getInt(PREF_ANSWER_REPEAT_COUNT, DEFAULT_ANSWER_REPEAT_COUNT))

        window.statusBarColor = android.graphics.Color.parseColor("#F6F0FA")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        setContent {
            VoiceAssistantTheme {
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    uiState = uiState.copy(
                        hasMicrophonePermission = granted,
                        hasAskedForMicrophonePermission = true,
                    )
                    if (granted && shouldStartListeningAfterPermissionGrant) {
                        startListening()
                    }
                    shouldStartListeningAfterPermissionGrant = false
                }

                val prefixImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) replacePrefixDictionaryFromJson(uri)
                }

                val mainImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) replaceMainDictionaryFromJson(uri)
                }

                LaunchedEffect(Unit) {
                    val hasPermission = hasAudioPermission()
                    uiState = uiState.copy(hasMicrophonePermission = hasPermission)
                    if (!hasPermission && !uiState.hasAskedForMicrophonePermission) {
                        uiState = uiState.copy(hasAskedForMicrophonePermission = true)
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        state = uiState,
                        onRequestMicrophone = {
                            if (hasAudioPermission()) {
                                uiState = uiState.copy(hasMicrophonePermission = true)
                                startListening()
                            } else {
                                shouldStartListeningAfterPermissionGrant = true
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenSettings = { uiState = uiState.copy(activeScreen = ActiveScreen.Settings) },
                        onBackFromSettings = { uiState = uiState.copy(activeScreen = ActiveScreen.Home) },
                        onImportMain = { mainImportLauncher.launch(arrayOf("application/json")) },
                        onImportPrefix = { prefixImportLauncher.launch(arrayOf("application/json")) },
                        onToggleListening = {
                            if (uiState.isListening) {
                                stopListening()
                            } else if (hasAudioPermission()) {
                                uiState = uiState.copy(hasMicrophonePermission = true)
                                startListening()
                            } else {
                                shouldStartListeningAfterPermissionGrant = true
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onRetryModel = { initModel() },
                        onDismissBanner = { uiState = uiState.copy(importBanner = null) },
                        onDismissRecognitionDialog = {
                            stopSpeakingResult()
                            uiState = uiState.copy(recognitionDialog = null, currentSpeakingAnswerIndex = null)
                        },
                        onRepeatRecognition = {
                            uiState = uiState.copy(recognitionDialog = null)
                            if (hasAudioPermission()) {
                                startListening()
                            } else {
                                shouldStartListeningAfterPermissionGrant = true
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenDictionaryDialog = { type ->
                            uiState = uiState.copy(
                                dictionaryDialog = when (type) {
                                    DictionaryType.Main -> DictionaryDialogState(
                                        title = "Основной словарь",
                                        entries = uiState.mainDictionaryEntries.map { "${it.first} → ${it.second}" },
                                    )
                                    DictionaryType.Prefix -> DictionaryDialogState(
                                        title = "Префиксный словарь",
                                        entries = uiState.prefixDictionaryEntries.map { "${it.first} → ${it.second.joinToString(", ")}" },
                                    )
                                },
                            )
                        },
                        onDismissDictionaryDialog = { uiState = uiState.copy(dictionaryDialog = null) },
                        onOpenSuggestionResult = { suggestion ->
                            openSuggestionResult(suggestion)
                        },
                        onReplayResult = {
                            uiState.resultScreenState?.let { speakResult(it.resultCode) }
                        },
                        onCloseResultScreen = {
                            stopSpeakingResult()
                            uiState = uiState.copy(
                                activeScreen = ActiveScreen.Home,
                                resultScreenState = null,
                                currentSpeakingAnswerIndex = null,
                                isTtsSummaryVisible = true,
                            )
                        },
                        onCloseTtsSummary = {
                            uiState = uiState.copy(isTtsSummaryVisible = false)
                        },
                        onDecreaseAnswerRepeats = { updateAnswerRepeatCount(uiState.answerRepeatCount - 1) },
                        onIncreaseAnswerRepeats = { updateAnswerRepeatCount(uiState.answerRepeatCount + 1) },
                        onTestTts = { speakPreview() },
                        onOpenAppSettings = { openAppSettings() },
                    )
                }
            }
        }

        refreshDictionariesFromDatabase()
        initModel()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            uiState = uiState.copy(ttsReady = false, ttsStatus = TtsStatus.Error)
            return
        }
        val localeStatus = textToSpeech?.setLanguage(RUSSIAN_LOCALE) ?: TextToSpeech.ERROR
        val isReady = localeStatus != TextToSpeech.LANG_MISSING_DATA && localeStatus != TextToSpeech.LANG_NOT_SUPPORTED
        uiState = uiState.copy(
            ttsReady = isReady,
            ttsStatus = if (isReady) TtsStatus.Ready else TtsStatus.Error,
        )
    }

    private fun initModel() {
        uiState = uiState.copy(
            modelStatus = LoadStatus.Loading("Vosk: установка"),
            importBanner = null,
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
                    uiState = uiState.copy(modelStatus = LoadStatus.Ready("Vosk: активен"))
                }
            },
            { error ->
                runOnUiThread {
                    uiState = uiState.copy(
                        modelStatus = LoadStatus.Error("Vosk: ошибка"),
                        importBanner = ImportBanner.Error(
                            title = "Vosk не инициализирован",
                            description = error.localizedMessage ?: "Модель повреждена или не распакована.",
                        ),
                    )
                }
            },
        )
    }

    private fun refreshDictionariesFromDatabase() {
        val prefixMap = database.getPhoneticDictionary()
        val mainMap = database.getMainDictionary()

        prefixDictionary = PrefixDictionary(
            letterToVariants = prefixMap,
            pronunciationToLetter = buildPronunciationToLetterMap(prefixMap),
        )
        mainDictionary = mainMap
        maxMainAbbreviationLength = mainMap.keys.maxOfOrNull { it.length } ?: 0
        rebuildRecognizer()

        uiState = uiState.copy(
            prefixDictionaryStatus = if (prefixMap.isEmpty()) LoadStatus.NotLoaded else LoadStatus.Ready("Префиксный словарь загружен"),
            mainDictionaryStatus = if (mainMap.isEmpty()) LoadStatus.NotLoaded else LoadStatus.Ready("Основной словарь загружен"),
            prefixEntriesCount = prefixMap.size,
            mainEntriesCount = mainMap.size,
            prefixDictionaryEntries = prefixMap.toList(),
            mainDictionaryEntries = mainMap.toList(),
            exampleCommands = mainMap.keys.take(3),
        )
    }

    private fun replacePrefixDictionaryFromJson(uri: Uri) {
        try {
            val fileName = requireJsonFile(uri)
            val directoryLabel = describeDictionaryLocation(uri)
            uiState = uiState.copy(importBanner = ImportBanner.Loading("Загрузка словарей", "Парсинг префиксного JSON..."))
            persistReadPermission(uri)
            val parsed = parsePrefixDictionary(readTextFromUri(uri))
            database.replacePhoneticDictionary(parsed.letterToVariants)
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(
                prefixFileName = buildDisplayPath(directoryLabel, fileName),
                importBanner = ImportBanner.Success(
                    title = "Статус: JSON загружен",
                    description = "Префиксный словарь: ${parsed.letterToVariants.size} шаблонов",
                ),
            )
        } catch (error: Exception) {
            uiState = uiState.copy(
                importBanner = ImportBanner.Error(
                    title = "Статус: ошибка формата",
                    description = error.localizedMessage ?: "Проверьте структуру JSON.",
                ),
            )
        }
    }

    private fun replaceMainDictionaryFromJson(uri: Uri) {
        try {
            val fileName = requireJsonFile(uri)
            val directoryLabel = describeDictionaryLocation(uri)
            uiState = uiState.copy(importBanner = ImportBanner.Loading("Загрузка словарей", "Парсинг основного JSON..."))
            persistReadPermission(uri)
            val parsed = parseMainDictionary(readTextFromUri(uri))
            database.replaceMainDictionary(parsed)
            refreshDictionariesFromDatabase()
            uiState = uiState.copy(
                mainFileName = buildDisplayPath(directoryLabel, fileName),
                importBanner = ImportBanner.Success(
                    title = "Статус: JSON загружен",
                    description = "${parsed.size} команды найдено",
                ),
            )
        } catch (error: Exception) {
            uiState = uiState.copy(
                importBanner = ImportBanner.Error(
                    title = "Статус: ошибка формата",
                    description = error.localizedMessage ?: "Проверьте структуру JSON.",
                ),
            )
        }
    }

    private fun requireJsonFile(uri: Uri): String {
        val fileName = queryFileName(uri)
        if (!fileName.endsWith(".json", ignoreCase = true)) {
            throw IllegalArgumentException("Файл словаря должен иметь расширение .json")
        }
        return fileName
    }

    private fun startListening() {
        rebuildRecognizer()
        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            uiState = uiState.copy(
                importBanner = ImportBanner.Error(
                    title = "Vosk не инициализирован",
                    description = "Распознавание пока недоступно.",
                ),
            )
            return
        }
        if (prefixDictionary.pronunciationToLetter.isEmpty() || mainDictionary.isEmpty()) {
            uiState = uiState.copy(
                importBanner = ImportBanner.Error(
                    title = "Словари не загружены",
                    description = "Загрузите основной и префиксный JSON, чтобы начать.",
                ),
            )
            return
        }
        if (speechService != null) return

        stableRecognizedText = ""
        currentPartialText = ""
        hasFinalizedRecognitionSession = false
        recognitionProgressSignature = ""
        uiState = uiState.copy(
            isListening = true,
            importBanner = null,
            recognitionDialog = null,
            recognizedText = "",
            recognizedAbbreviation = "",
            recognitionResult = "",
        )

        speechService = SpeechService(currentRecognizer, SAMPLE_RATE).also {
            it.startListening(this)
        }
        resetRecognitionTimeout()
    }

    private fun stopListening(finalize: Boolean = true) {
        if (speechService == null && !uiState.isListening) return
        cancelRecognitionTimeout()
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
        val grammar = buildRecognitionGrammar()
        recognizer = if (grammar != null) {
            Recognizer(currentModel, SAMPLE_RATE, grammar)
        } else {
            Recognizer(currentModel, SAMPLE_RATE)
        }
    }

    private fun buildRecognitionGrammar(): String? {
        val tokenVariants = prefixDictionary.pronunciationToLetter.keys
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
            prefixDictionary.letterToVariants[letter.toString()]
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
        processRecognition(hypothesis, RecognitionMode.Partial)
    }

    override fun onResult(hypothesis: String?) {
        processRecognition(hypothesis, RecognitionMode.StableChunk)
    }

    override fun onFinalResult(hypothesis: String?) {
        processRecognition(hypothesis, RecognitionMode.FinalChunk)
        stopListening()
    }

    override fun onError(exception: Exception?) {
        runOnUiThread {
            uiState = uiState.copy(
                importBanner = ImportBanner.Error(
                    title = "Vosk: ошибка",
                    description = exception?.localizedMessage ?: "Ошибка распознавания.",
                ),
            )
            stopListening(finalize = false)
        }
    }

    override fun onTimeout() {
        runOnUiThread {
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
        val abbreviation = decodeAbbreviation(combinedText, prefixDictionary.pronunciationToLetter).orEmpty()
        val shouldAutoStop = shouldAutoStopRecognition(abbreviation)
        val nextSignature = "$combinedText|$abbreviation"

        runOnUiThread {
            uiState = uiState.copy(
                recognizedText = combinedText,
                recognizedAbbreviation = abbreviation,
            )
            if (nextSignature != recognitionProgressSignature) {
                recognitionProgressSignature = nextSignature
                resetRecognitionTimeout()
            }
            if (shouldAutoStop && uiState.isListening) {
                stopListening()
            }
        }
    }

    private fun finalizeRecognitionSession() {
        if (hasFinalizedRecognitionSession) return
        hasFinalizedRecognitionSession = true

        val combinedText = mergeRecognizedText(stableRecognizedText, currentPartialText)
        val abbreviation = decodeAbbreviation(combinedText, prefixDictionary.pronunciationToLetter).orEmpty()
        val dialog = resolveRecognitionDialog(combinedText, abbreviation)

        if (dialog is RecognitionDialog.Success) {
            uiState = uiState.copy(
                recognizedText = combinedText,
                recognizedAbbreviation = abbreviation,
                recognitionResult = dialog.resultCode,
                recognitionDialog = null,
                isTtsSummaryVisible = true,
                resultScreenState = ResultScreenState(
                    abbreviation = dialog.abbreviation,
                    resultCode = dialog.resultCode,
                    spokenText = dialog.spokenText,
                ),
                activeScreen = ActiveScreen.Result,
            )
            speakResult(dialog.resultCode)
        } else {
            uiState = uiState.copy(
                recognizedText = combinedText,
                recognizedAbbreviation = abbreviation,
                recognitionResult = "",
                recognitionDialog = dialog,
                resultScreenState = null,
            )
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

    private fun resolveRecognitionDialog(recognizedText: String, abbreviation: String): RecognitionDialog {
        if (recognizedText.isBlank() || abbreviation.isBlank()) {
            return RecognitionDialog.Empty
        }

        val exactResult = mainDictionary[abbreviation]
        if (exactResult != null) {
            return RecognitionDialog.Success(
                abbreviation = abbreviation,
                resultCode = exactResult,
                spokenText = buildSpokenSummary(exactResult),
            )
        }

        val prefix = abbreviation.take(PREFIX_HINT_LENGTH)
        val suggestions = mainDictionary.keys
            .filter { prefix.isNotBlank() && it.startsWith(prefix) }
            .take(3)

        return if (suggestions.isNotEmpty()) {
            RecognitionDialog.Partial(
                abbreviation = abbreviation,
                prefix = prefix,
                suggestions = suggestions,
            )
        } else {
            RecognitionDialog.NotFound(abbreviation)
        }
    }

    private fun handleRecognitionInactivityTimeout() {
        if (!uiState.isListening) return
        val combinedText = mergeRecognizedText(stableRecognizedText, currentPartialText)
        val abbreviation = decodeAbbreviation(combinedText, prefixDictionary.pronunciationToLetter).orEmpty()
        hasFinalizedRecognitionSession = true
        uiState = uiState.copy(
            isListening = false,
            recognizedText = combinedText,
            recognizedAbbreviation = abbreviation,
            recognitionDialog = RecognitionDialog.Partial(
                abbreviation = abbreviation.ifBlank { "..." },
                prefix = abbreviation.take(PREFIX_HINT_LENGTH),
                suggestions = mainDictionary.keys
                    .filter { abbreviation.isNotBlank() && it.startsWith(abbreviation.take(PREFIX_HINT_LENGTH)) }
                    .take(3),
                timeoutTriggered = true,
            ),
        )
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        currentPartialText = ""
        cancelRecognitionTimeout()
    }

    private fun resetRecognitionTimeout() {
        mainHandler.removeCallbacks(recognitionTimeoutRunnable)
        mainHandler.postDelayed(recognitionTimeoutRunnable, RECOGNITION_IDLE_TIMEOUT_MS)
    }

    private fun cancelRecognitionTimeout() {
        mainHandler.removeCallbacks(recognitionTimeoutRunnable)
    }

    private fun openSuggestionResult(abbreviation: String) {
        val result = mainDictionary[abbreviation] ?: return
            uiState = uiState.copy(
                recognitionDialog = null,
            isTtsSummaryVisible = true,
                resultScreenState = ResultScreenState(
                    abbreviation = abbreviation,
                    resultCode = result,
                spokenText = buildSpokenSummary(result),
            ),
            recognitionResult = result,
            recognizedAbbreviation = abbreviation,
            currentSpeakingAnswerIndex = null,
            activeScreen = ActiveScreen.Result,
        )
        speakResult(result)
    }

    private fun speakResult(value: String) {
        if (!uiState.ttsReady) return
        stopSpeakingResult()
        val digits = value.toCharArray().toList()
        val repeatCount = uiState.answerRepeatCount.coerceAtLeast(1)
        digits.forEachIndexed { answerIndex, digit ->
            repeat(repeatCount) { repeatIndex ->
                textToSpeech?.speak(
                    buildAnswerSpeech(answerIndex, digit),
                    if (answerIndex == 0 && repeatIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    buildAnswerUtteranceId(
                        answerIndex = answerIndex,
                        repeatIndex = repeatIndex,
                        isLast = answerIndex == digits.lastIndex && repeatIndex == repeatCount - 1,
                    ),
                )
            }
        }
    }

    private fun speakPreview() {
        if (!uiState.ttsReady) return
        stopSpeakingResult()
        textToSpeech?.speak("Тест синтеза речи", TextToSpeech.QUEUE_FLUSH, null, TTS_PREVIEW_UTTERANCE_ID)
    }

    private fun stopSpeakingResult() {
        textToSpeech?.stop()
        uiState = uiState.copy(
            currentSpeakingAnswerIndex = null,
            ttsStatus = if (uiState.ttsReady) TtsStatus.Ready else uiState.ttsStatus,
        )
    }

    private fun buildAnswerSpeech(answerIndex: Int, digit: Char): String =
        "вопрос ${answerIndex + 1} ответ ${digitToSpeech(digit)}"

    private fun buildSpokenSummary(value: String): String =
        value.mapIndexed { index, digit ->
            "вопрос ${numberToSpeech(index + 1)} · ответ ${digitToSpeech(digit)}"
        }.joinToString(" · ")

    private fun buildAnswerUtteranceId(answerIndex: Int, repeatIndex: Int, isLast: Boolean): String =
        "$ANSWER_UTTERANCE_PREFIX-$answerIndex-$repeatIndex-${if (isLast) 1 else 0}"

    private fun parseSpeakingAnswerIndex(utteranceId: String?): Int? {
        if (utteranceId == null || !utteranceId.startsWith("$ANSWER_UTTERANCE_PREFIX-")) return null
        return utteranceId.split("-").getOrNull(1)?.toIntOrNull()
    }

    private fun isLastAnswerUtterance(utteranceId: String?): Boolean =
        utteranceId?.split("-")?.lastOrNull() == "1"

    private fun updateAnswerRepeatCount(nextValue: Int) {
        val clamped = nextValue.coerceIn(MIN_ANSWER_REPEAT_COUNT, MAX_ANSWER_REPEAT_COUNT)
        preferences.edit().putInt(PREF_ANSWER_REPEAT_COUNT, clamped).apply()
        uiState = uiState.copy(answerRepeatCount = clamped)
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

    private fun numberToSpeech(value: Int): String = when (value) {
        1 -> "один"
        2 -> "два"
        3 -> "три"
        4 -> "четыре"
        5 -> "пять"
        6 -> "шесть"
        7 -> "семь"
        8 -> "восемь"
        9 -> "девять"
        10 -> "десять"
        else -> value.toString()
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

    private fun parsePrefixDictionary(jsonText: String): PrefixDictionary {
        val root = parseJsonObject(jsonText)
        val letterToVariants = linkedMapOf<String, List<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val letter = normalizeLetter(key)
            if (letter.length != 1) {
                throw IllegalArgumentException("Префиксный словарь: ключ \"$key\" должен содержать одну букву.")
            }
            val value = root.get(key)
            if (value !is JSONArray) {
                throw IllegalArgumentException("Для ключа $letter ожидается массив значений.")
            }
            val variants = buildList {
                for (index in 0 until value.length()) {
                    if (value.isNull(index)) {
                        throw IllegalArgumentException("Префиксный словарь: пустое значение в массиве для \"$letter\".")
                    }
                    val rawVariant = value.get(index)
                    if (rawVariant !is String) {
                        throw IllegalArgumentException("Префиксный словарь: все значения для \"$letter\" должны быть строками.")
                    }
                    val variant = normalizeSpeech(rawVariant)
                    if (variant.isBlank()) {
                        throw IllegalArgumentException("Префиксный словарь: пустая строка в массиве для \"$letter\".")
                    }
                    add(variant)
                }
            }.distinct()
            if (variants.isEmpty()) {
                throw IllegalArgumentException("Префиксный словарь: для \"$letter\" нужен непустой массив.")
            }
            letterToVariants[letter] = variants
        }
        return PrefixDictionary(
            letterToVariants = letterToVariants,
            pronunciationToLetter = buildPronunciationToLetterMap(letterToVariants),
        )
    }

    private fun parseMainDictionary(jsonText: String): Map<String, String> {
        val root = parseJsonObject(jsonText)
        val result = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val abbreviation = normalizeAbbreviationKey(key)
            if (abbreviation.isBlank()) {
                throw IllegalArgumentException("Основной словарь: пустой ключ недопустим.")
            }
            val rawValue = root.get(key)
            if (rawValue !is String) {
                throw IllegalArgumentException("Основной словарь: значение для \"$key\" должно быть строкой.")
            }
            val value = rawValue.trim()
            if (value.isBlank()) {
                throw IllegalArgumentException("Основной словарь: значение для \"$key\" не должно быть пустым.")
            }
            if (!value.all { it.isDigit() }) {
                throw IllegalArgumentException("Основной словарь: значение для \"$key\" должно содержать только цифры.")
            }
            result[abbreviation] = value
        }
        return result
    }

    private fun parseJsonObject(jsonText: String): JSONObject {
        val normalized = jsonText.trim()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Файл пустой.")
        }
        if (!normalized.startsWith("{")) {
            throw IllegalArgumentException("Ожидается JSON-объект.")
        }
        return try {
            JSONObject(normalized)
        } catch (_: Exception) {
            throw IllegalArgumentException("Некорректный JSON.")
        }
    }

    private fun buildPronunciationToLetterMap(entries: Map<String, List<String>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        entries.forEach { (letter, variants) ->
            variants.forEach { variant ->
                result.putIfAbsent(normalizeSpeech(variant), letter)
            }
        }
        return result
    }

    private fun normalizeLetter(value: String): String = value.trim().uppercase(RUSSIAN_LOCALE)

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
        return uri.lastPathSegment ?: "dictionary.json"
    }

    private fun describeDictionaryLocation(uri: Uri): String {
        val path = uri.path.orEmpty()
        return when {
            "download" in path.lowercase() -> "file/storage"
            "document" in path.lowercase() -> "file/storage"
            path.isNotBlank() -> "file/storage"
            else -> "file/storage"
        }
    }

    private fun buildDisplayPath(directoryLabel: String, fileName: String): String =
        "$directoryLabel/$fileName"

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .recoverCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
        cancelRecognitionTimeout()
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000.0f
        private const val UNPACKED_MODEL_DIR = "vosk-model-cache-v4"
        private const val MAX_GRAMMAR_PHRASES = 5000
        private const val MAX_VARIANT_PHRASES_PER_ABBREVIATION = 256
        private const val PREFIX_HINT_LENGTH = 3
        private const val RECOGNITION_IDLE_TIMEOUT_MS = 6_000L
        private const val PREFERENCES_NAME = "voice_assistant_preferences"
        private const val PREF_ANSWER_REPEAT_COUNT = "answer_repeat_count"
        private const val DEFAULT_ANSWER_REPEAT_COUNT = 5
        private const val MIN_ANSWER_REPEAT_COUNT = 1
        private const val MAX_ANSWER_REPEAT_COUNT = 10
        private const val ANSWER_UTTERANCE_PREFIX = "answer"
        private const val TTS_PREVIEW_UTTERANCE_ID = "tts-preview"
        private val RUSSIAN_LOCALE: Locale = Locale.forLanguageTag("ru-RU")
        private val NON_LETTER_OR_DIGIT_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

private data class AppUiState(
    val activeScreen: ActiveScreen = ActiveScreen.Home,
    val modelStatus: LoadStatus = LoadStatus.Loading("Vosk: установка"),
    val mainDictionaryStatus: LoadStatus = LoadStatus.NotLoaded,
    val prefixDictionaryStatus: LoadStatus = LoadStatus.NotLoaded,
    val mainEntriesCount: Int = 0,
    val prefixEntriesCount: Int = 0,
    val prefixDictionaryEntries: List<Pair<String, List<String>>> = emptyList(),
    val mainDictionaryEntries: List<Pair<String, String>> = emptyList(),
    val mainFileName: String = "не загружен",
    val prefixFileName: String = "не загружен",
    val exampleCommands: List<String> = emptyList(),
    val answerRepeatCount: Int = 5,
    val currentSpeakingAnswerIndex: Int? = null,
    val recognizedText: String = "",
    val recognizedAbbreviation: String = "",
    val recognitionResult: String = "",
    val resultScreenState: ResultScreenState? = null,
    val isTtsSummaryVisible: Boolean = true,
    val recognitionDialog: RecognitionDialog? = null,
    val dictionaryDialog: DictionaryDialogState? = null,
    val importBanner: ImportBanner? = null,
    val isListening: Boolean = false,
    val ttsReady: Boolean = false,
    val ttsStatus: TtsStatus = TtsStatus.Loading,
    val hasMicrophonePermission: Boolean = false,
    val hasAskedForMicrophonePermission: Boolean = false,
)

private enum class ActiveScreen {
    Home,
    Settings,
    Result,
}

private enum class TtsStatus {
    Loading,
    Ready,
    Speaking,
    Error,
}

private data class PrefixDictionary(
    val letterToVariants: Map<String, List<String>> = emptyMap(),
    val pronunciationToLetter: Map<String, String> = emptyMap(),
)

private enum class RecognitionMode {
    Partial,
    StableChunk,
    FinalChunk,
}

private enum class DictionaryType {
    Main,
    Prefix,
}

private data class DictionaryDialogState(
    val title: String,
    val entries: List<String>,
)

private data class ResultScreenState(
    val abbreviation: String,
    val resultCode: String,
    val spokenText: String,
)

private sealed interface LoadStatus {
    val message: String

    data object NotLoaded : LoadStatus {
        override val message: String = "не загружен"
    }

    data class Loading(override val message: String) : LoadStatus
    data class Ready(override val message: String) : LoadStatus
    data class Error(override val message: String) : LoadStatus
}

private sealed interface ImportBanner {
    val title: String
    val description: String

    data class Loading(
        override val title: String,
        override val description: String,
    ) : ImportBanner

    data class Success(
        override val title: String,
        override val description: String,
    ) : ImportBanner

    data class Error(
        override val title: String,
        override val description: String,
    ) : ImportBanner
}

private sealed interface RecognitionDialog {
    data class Success(
        val abbreviation: String,
        val resultCode: String,
        val spokenText: String,
    ) : RecognitionDialog

    data class Partial(
        val abbreviation: String,
        val prefix: String,
        val suggestions: List<String>,
        val timeoutTriggered: Boolean = false,
    ) : RecognitionDialog

    data class NotFound(val abbreviation: String) : RecognitionDialog
    data object Empty : RecognitionDialog
}

@Composable
private fun AppRoot(
    state: AppUiState,
    onRequestMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackFromSettings: () -> Unit,
    onImportMain: () -> Unit,
    onImportPrefix: () -> Unit,
    onToggleListening: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissBanner: () -> Unit,
    onDismissRecognitionDialog: () -> Unit,
    onRepeatRecognition: () -> Unit,
    onOpenDictionaryDialog: (DictionaryType) -> Unit,
    onDismissDictionaryDialog: () -> Unit,
    onOpenSuggestionResult: (String) -> Unit,
    onReplayResult: () -> Unit,
    onCloseResultScreen: () -> Unit,
    onCloseTtsSummary: () -> Unit,
    onDecreaseAnswerRepeats: () -> Unit,
    onIncreaseAnswerRepeats: () -> Unit,
    onTestTts: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AppBackground,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 6.dp),
        ) {
            when {
                state.modelStatus is LoadStatus.Loading -> SplashScreen(state)
                state.activeScreen == ActiveScreen.Settings -> SettingsScreen(
                    state = state,
                    onBack = onBackFromSettings,
                    onTestTts = onTestTts,
                    onOpenMainDictionary = { onOpenDictionaryDialog(DictionaryType.Main) },
                    onOpenPrefixDictionary = { onOpenDictionaryDialog(DictionaryType.Prefix) },
                    onDecreaseAnswerRepeats = onDecreaseAnswerRepeats,
                    onIncreaseAnswerRepeats = onIncreaseAnswerRepeats,
                )
                state.activeScreen == ActiveScreen.Result && state.resultScreenState != null -> ResultScreen(
                    state = state,
                    result = state.resultScreenState,
                    onBack = onCloseResultScreen,
                    onReplay = onReplayResult,
                    onDone = onCloseResultScreen,
                    onCloseTtsSummary = onCloseTtsSummary,
                )
                !state.hasMicrophonePermission && state.hasAskedForMicrophonePermission -> MicrophonePermissionScreen(
                    onOpenAppSettings = onOpenAppSettings,
                )
                else -> HomeScreen(
                    state = state,
                    onOpenSettings = onOpenSettings,
                    onImportMain = onImportMain,
                    onImportPrefix = onImportPrefix,
                    onToggleListening = onToggleListening,
                    onRetryModel = onRetryModel,
                    onDismissBanner = onDismissBanner,
                )
            }

            state.recognitionDialog?.let { dialog ->
                RecognitionDialogSheet(
                    dialog = dialog,
                    onDismiss = onDismissRecognitionDialog,
                    onRepeat = onRepeatRecognition,
                    onOpenSuggestionResult = onOpenSuggestionResult,
                    currentSpeakingAnswerIndex = state.currentSpeakingAnswerIndex,
                )
            }
            state.dictionaryDialog?.let { dialog ->
                DictionaryDialogSheet(dialog = dialog, onDismiss = onDismissDictionaryDialog)
            }
        }
    }
}

@Composable
private fun SplashScreen(state: AppUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPillRow(state)
        Spacer(modifier = Modifier.weight(1f))
        CircleCenter(icon = "S", backgroundColor = AppPurple, textColor = Color.White)
        Spacer(modifier = Modifier.height(22.dp))
        Text("Установка...", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppText)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Пакет Speech App Pro\nзагрузка модели Vosk",
            color = AppMutedText,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = AppPurple,
            trackColor = AppTrack,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Подготовка ресурсов", color = AppMutedText, fontSize = 15.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("...", color = AppPurple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.weight(1f))
        OutlineActionButton(
            text = "Отменить",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    onOpenSettings: () -> Unit,
    onImportMain: () -> Unit,
    onImportPrefix: () -> Unit,
    onToggleListening: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPillRow(state, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Главный экран",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = AppText,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = AppText, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        val centerColor = when (state.modelStatus) {
            is LoadStatus.Error -> Color(0xFFF7D8D4)
            else -> AppSoftPurple
        }
        val centerIcon = when {
            state.modelStatus is LoadStatus.Error -> Icons.Filled.ErrorOutline
            !state.hasMicrophonePermission -> Icons.Filled.MicOff
            else -> Icons.Filled.Mic
        }
        val centerTint = when {
            state.modelStatus is LoadStatus.Error -> AppError
            !state.hasMicrophonePermission -> AppError
            state.isListening -> AppPurple
            else -> Color(0xFF3E1380)
        }
        CircleCenter(
            icon = centerIcon,
            iconTint = centerTint,
            backgroundColor = centerColor,
            onClick = onToggleListening,
        )

        Spacer(modifier = Modifier.height(14.dp))

        when (val banner = state.importBanner) {
            is ImportBanner.Loading -> {
                LoadingCard(banner)
            }
            is ImportBanner.Success -> {
                BannerCard(banner, AppSuccessBg, AppSuccess, onDismissBanner)
            }
            is ImportBanner.Error -> {
                BannerCard(banner, AppErrorBg, AppError, onDismissBanner)
                if (state.modelStatus is LoadStatus.Error) {
                    Spacer(modifier = Modifier.height(14.dp))
                    PrimaryActionButton(
                        text = "Переустановить модель",
                        onClick = onRetryModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlineActionButton(
                        text = "Перезагрузить",
                        icon = Icons.Filled.Refresh,
                        onClick = onDismissBanner,
                        modifier = Modifier.fillMaxWidth(),
                        height = 50.dp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SoftActionButton(
                        text = "Выбрать другой файл",
                        icon = Icons.Filled.FolderOpen,
                        onClick = onImportMain,
                        modifier = Modifier.fillMaxWidth(),
                        destructive = true,
                    )
                }
            }
            null -> {
                if (state.mainEntriesCount == 0 || state.prefixEntriesCount == 0) {
                    EmptyDictionariesCard(state)
                } else {
                    ReadyStateCard(state)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        PrimaryActionButton(
            text = if (state.isListening) "Слушаю..." else "Слушать",
            icon = Icons.Filled.Mic,
            onClick = onToggleListening,
            enabled = state.modelStatus is LoadStatus.Ready &&
                state.mainEntriesCount > 0 &&
                state.prefixEntriesCount > 0 &&
                state.hasMicrophonePermission &&
                state.importBanner !is ImportBanner.Loading,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlineActionButton(
                text = "Осн.\nJSON",
                icon = Icons.Filled.Upload,
                onClick = onImportMain,
                modifier = Modifier.weight(1f),
            )
            OutlineActionButton(
                text = "Преф.\nJSON",
                icon = Icons.Filled.Upload,
                onClick = onImportPrefix,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onTestTts: () -> Unit,
    onOpenMainDictionary: () -> Unit,
    onOpenPrefixDictionary: () -> Unit,
    onDecreaseAnswerRepeats: () -> Unit,
    onIncreaseAnswerRepeats: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        StatusPillRow(state)
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AppText)
            }
            Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppText)
        }
        Spacer(modifier = Modifier.height(8.dp))

        SettingsCard("Vosk модель", "ru-0.22 (офлайн, 45 МБ)")
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard(
            "TTS голос",
            when (state.ttsStatus) {
                TtsStatus.Ready -> "Русский стандартный"
                TtsStatus.Speaking -> "Русский стандартный"
                TtsStatus.Loading -> "Подготовка..."
                TtsStatus.Error -> "Голос недоступен"
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        RepeatCountCard(
            value = state.answerRepeatCount,
            onDecrease = onDecreaseAnswerRepeats,
            onIncrease = onIncreaseAnswerRepeats,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard(
            "Основной словарь",
            state.mainFileName,
            status = if (state.mainEntriesCount > 0) "Загружено: ${state.mainEntriesCount} команды" else "Не загружен",
            onClick = onOpenMainDictionary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard(
            "Префиксный словарь",
            state.prefixFileName,
            status = if (state.prefixEntriesCount > 0) "Загружено: ${state.prefixEntriesCount} шаблонов" else "Не загружен",
            onClick = onOpenPrefixDictionary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SampleJsonCard()
        Spacer(modifier = Modifier.height(8.dp))
        OutlineActionButton(
            text = "Тест TTS",
            icon = Icons.Filled.VolumeUp,
            onClick = onTestTts,
            modifier = Modifier.fillMaxWidth(),
            height = 50.dp,
        )
    }
}

@Composable
private fun MicrophonePermissionScreen(onOpenAppSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(AppSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(AppError)
            Text("Микрофон: нет доступа", color = AppError, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("TTS: готов", color = AppMutedText, fontSize = 12.sp)
            Text("офлайн-режим", color = AppMutedText, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text("Голосовой помощник", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppText)
        Spacer(modifier = Modifier.weight(1f))
        CircleCenter(icon = Icons.Filled.MicOff, iconTint = AppError, backgroundColor = AppErrorBg)
        Spacer(modifier = Modifier.height(26.dp))
        Text("Нужен доступ к микрофону", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppText, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Без разрешения Android приложение не может слушать команды.\n\nОткройте системные настройки и разрешите доступ к микрофону для этого приложения.",
            color = AppMutedText,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryActionButton(
            text = "Открыть настройки",
            icon = Icons.Filled.Settings,
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text("Не сейчас", color = AppPurple, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecognitionDialogSheet(
    dialog: RecognitionDialog,
    onDismiss: () -> Unit,
    onRepeat: () -> Unit,
    onOpenSuggestionResult: (String) -> Unit,
    currentSpeakingAnswerIndex: Int?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = Color(0xFFF7F2FA),
        text = {
            when (dialog) {
                is RecognitionDialog.Success -> Text("Результат открыт на отдельном экране.")
                is RecognitionDialog.Partial -> PartialDialogContent(dialog, onDismiss, onRepeat, onOpenSuggestionResult)
                is RecognitionDialog.NotFound -> NotFoundDialogContent(dialog, onDismiss, onRepeat)
                RecognitionDialog.Empty -> EmptyDialogContent(onDismiss, onRepeat)
            }
        },
    )
}

@Composable
private fun ResultScreen(
    state: AppUiState,
    result: ResultScreenState,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
    onCloseTtsSummary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        StatusPillRow(state)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Расшифровка ответа",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AppText,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("КОМАНДА", color = AppMutedText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                result.abbreviation.toCharArray().joinToString(" "),
                color = AppPurple,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildAnnotatedString {
                    append("код: ")
                    result.resultCode.forEachIndexed { index, char ->
                        if (index > 0) append(" · ")
                        pushStyle(
                            SpanStyle(
                                color = if (state.currentSpeakingAnswerIndex == index) AppPurple else AppMutedText,
                                fontWeight = if (state.currentSpeakingAnswerIndex == index) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                        append(char.toString())
                        pop()
                    }
                },
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(14.dp))
            AnswerBlocksSection(
                resultCode = result.resultCode,
                currentSpeakingAnswerIndex = state.currentSpeakingAnswerIndex,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.isTtsSummaryVisible) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppSuccessBg),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TTS ОЗВУЧИВАНИЕ", color = Color(0xFF3F6A32), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onCloseTtsSummary, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color(0xFF3F6A32), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(result.spokenText, color = Color(0xFF2E5F31), lineHeight = 20.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryActionButton(
                text = "Повторить",
                onClick = onReplay,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Refresh,
            )
            OutlineActionButton(
                text = "Готово",
                onClick = onDone,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Check,
                height = 62.dp,
            )
        }
    }
}

@Composable
private fun DictionaryDialogSheet(
    dialog: DictionaryDialogState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = Color(0xFFF7F2FA),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dialog.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
                val visibleEntries = dialog.entries.take(12)
                if (visibleEntries.isEmpty()) {
                    Text("Словарь пуст.", color = AppMutedText)
                } else {
                    visibleEntries.forEach { entry ->
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                            Text(
                                text = entry,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                color = AppText,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
                if (dialog.entries.size > visibleEntries.size) {
                    Text("Показаны первые ${visibleEntries.size} записей из ${dialog.entries.size}.", color = AppMutedText, fontSize = 12.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Закрыть", color = AppPurple, fontWeight = FontWeight.SemiBold) }
                }
            }
        },
    )
}

@Composable
private fun SuccessDialogContent(
    dialog: RecognitionDialog.Success,
    onDismiss: () -> Unit,
    currentSpeakingAnswerIndex: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = AppPurple)
        Text("Распознано: ${dialog.abbreviation}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
        AnswerBlocksSection(
            resultCode = dialog.resultCode,
            currentSpeakingAnswerIndex = currentSpeakingAnswerIndex,
        )
        Text("1-й проход: совпадение\n2-й проход: точное совпадение", color = AppMutedText, lineHeight = 24.sp)
        Card(colors = CardDefaults.cardColors(containerColor = AppSuccessBg)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("КОД ДЛЯ ДЕЙСТВИЯ", color = Color(0xFF3F6A32), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(dialog.resultCode.toCharArray().joinToString("  "), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF013220))
                Spacer(modifier = Modifier.height(12.dp))
                Text("TTS: «${dialog.spokenText}»", color = Color(0xFF3D6C3C), textAlign = TextAlign.Center)
            }
        }
        Row {
            TextButton(onClick = onDismiss) { Text("Готово", color = AppPurple, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AnswerBlocksSection(
    resultCode: String,
    currentSpeakingAnswerIndex: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        resultCode.forEachIndexed { index, answerDigit ->
            QuestionAnswerCard(
                questionIndex = index,
                answerDigit = answerDigit,
                isSpeaking = currentSpeakingAnswerIndex == index,
            )
        }
    }
}

@Composable
private fun QuestionAnswerCard(
    questionIndex: Int,
    answerDigit: Char,
    isSpeaking: Boolean,
) {
    val options = listOf('1', '2', '3')
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSpeaking) Color(0xFFEADFFD) else AppSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("ВОПРОС ${questionIndex + 1}", color = AppMutedText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Текст вопроса номер ${questionIndex + 1}?", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val isSelected = option == answerDigit
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isSelected && isSpeaking -> Color(0xFFE9DEFC)
                                isSelected -> Color(0xFFF0E7FC)
                                isSpeaking -> Color(0xFFEAE5EE)
                                else -> Color(0xFFEAE5EE)
                            },
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) AppPurple else Color.Transparent,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isSelected) "$option ✓" else option.toString(),
                                color = if (isSelected) AppPurple else AppMutedText,
                                fontSize = 18.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartialDialogContent(
    dialog: RecognitionDialog.Partial,
    onDismiss: () -> Unit,
    onRepeat: () -> Unit,
    onOpenSuggestionResult: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = Color(0xFFFF7B00))
        Text("Уточните команду", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
        Text(
            if (dialog.timeoutTriggered) {
                "6 секунд нет новых букв.\nРаспознавание остановлено, нужно уточнение команды."
            } else {
                "1-й проход (префикс «${dialog.prefix}»): совпадение\n2-й проход (аббревиатура): не найдено"
            },
            color = AppMutedText,
            lineHeight = 24.sp,
        )
        if (dialog.suggestions.isNotEmpty()) {
            Text("Похожие команды", color = AppMutedText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dialog.suggestions.forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(AppSurface)
                            .clickable { onOpenSuggestionResult(suggestion) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(suggestion, color = AppText, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = AppPurple, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onRepeat,
                colors = ButtonDefaults.buttonColors(containerColor = AppPurple),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Повторить", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NotFoundDialogContent(dialog: RecognitionDialog.NotFound, onDismiss: () -> Unit, onRepeat: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AppError)
        Text("Не найдено", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
        Text(
            "1-й проход (префикс): не найдено\n2-й проход (аббревиатура): не найдено",
            color = AppMutedText,
            lineHeight = 24.sp,
        )
        Card(colors = CardDefaults.cardColors(containerColor = AppErrorBg)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("НЕ НАЙДЕНО", color = Color(0xFF8F433A), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Text(dialog.abbreviation.toCharArray().joinToString(" "), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4A1B12))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Команда отсутствует в словаре.\nПроверьте произношение или загрузите расширенный JSON.",
                    textAlign = TextAlign.Center,
                    color = Color(0xFF7C4B45),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = AppPurple, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onRepeat,
                colors = ButtonDefaults.buttonColors(containerColor = AppPurple),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Повторить", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyDialogContent(onDismiss: () -> Unit, onRepeat: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color(0xFFFF7B00))
        Text("Не распознано", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
        Text(
            "Vosk вернул пустой результат.\n\nПроизнесите команду чётче и ближе к микрофону.",
            color = AppMutedText,
            lineHeight = 24.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = AppPurple, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onRepeat,
                colors = ButtonDefaults.buttonColors(containerColor = AppPurple),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Повторить", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReadyStateCard(state: AppUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.exampleCommands.isNotEmpty()) {
            Text("Примеры команд:", color = AppMutedText, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                state.exampleCommands.joinToString(" · "),
                color = AppPurple,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CountCard("Основной", "${state.mainEntriesCount} команды", modifier = Modifier.weight(1f))
            CountCard("Префиксный", "${state.prefixEntriesCount} шаблонов", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EmptyDictionariesCard(state: AppUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WarningCountCard("Основной", state.mainEntriesCount > 0, modifier = Modifier.weight(1f))
            WarningCountCard("Префиксный", state.prefixEntriesCount > 0, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = AppErrorBg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AppError)
                Text("Загрузите словари, чтобы начать", color = AppText, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun LoadingCard(banner: ImportBanner.Loading) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE7E0F5)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null, tint = AppPurple)
                }
                Column {
                    Text(banner.title.uppercase(UiRussianLocale), color = AppMutedText, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Text(banner.description, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(20.dp)),
                color = AppPurple,
                trackColor = AppTrack,
            )
        }
    }
}

@Composable
private fun BannerCard(
    banner: ImportBanner,
    backgroundColor: Color,
    accentColor: Color,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (banner) {
                        is ImportBanner.Success -> Icons.Filled.Check
                        is ImportBanner.Error -> Icons.Filled.ErrorOutline
                        is ImportBanner.Loading -> Icons.Filled.Upload
                    },
                    contentDescription = null,
                    tint = accentColor,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(banner.title.uppercase(UiRussianLocale), color = accentColor, fontWeight = FontWeight.Bold)
                Text(banner.description, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = AppMutedText)
            }
        }
    }
}

@Composable
private fun CountCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title.uppercase(UiRussianLocale), color = AppMutedText, fontSize = 13.sp)
            Text(value, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WarningCountCard(title: String, loaded: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (loaded) AppSurface else AppErrorBg),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title.uppercase(UiRussianLocale), color = AppMutedText, fontSize = 13.sp)
            Text(if (loaded) "загружен" else "не загружен", color = if (loaded) AppText else AppError, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusPillRow(state: AppUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AppSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            StatusPillItem(
                text = when (state.modelStatus) {
                    is LoadStatus.Ready -> "Vosk: активен"
                    is LoadStatus.Loading -> "Vosk: установка"
                    is LoadStatus.Error -> "Vosk: ошибка"
                    LoadStatus.NotLoaded -> "Vosk: пусто"
                },
                color = when (state.modelStatus) {
                    is LoadStatus.Ready -> AppSuccess
                    is LoadStatus.Loading -> Color(0xFFE67E22)
                    is LoadStatus.Error -> AppError
                    LoadStatus.NotLoaded -> AppMuted
                },
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            StatusPillItem(
                text = when (state.ttsStatus) {
                    TtsStatus.Ready -> "TTS: готов"
                    TtsStatus.Speaking -> "TTS: говорит"
                    TtsStatus.Loading -> "TTS: запуск"
                    TtsStatus.Error -> "TTS: ошибка"
                },
                color = when (state.ttsStatus) {
                    TtsStatus.Ready -> AppSuccess
                    TtsStatus.Speaking -> AppSuccess
                    TtsStatus.Loading -> Color(0xFFE67E22)
                    TtsStatus.Error -> AppError
                },
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            StatusPillItem(text = "офлайн-режим", color = AppMuted)
        }
    }
}

@Composable
private fun StatusPillItem(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color)
        Text(text, color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun CircleCenter(
    icon: Any,
    iconTint: Color = Color(0xFF38127A),
    backgroundColor: Color,
    textColor: Color = AppText,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(150.dp)
            .border(2.dp, Color(0xFFEDE3FA), CircleShape)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = (
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(backgroundColor)
                ).let { base ->
                    if (onClick != null) {
                        base.clickable { onClick() }
                    } else {
                        base
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (icon) {
                is String -> Text(icon, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = textColor)
                is androidx.compose.ui.graphics.vector.ImageVector -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(54.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, value: String, status: String? = null, onClick: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(title.uppercase(UiRussianLocale), color = AppMutedText, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            if (status != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(status, color = AppSuccess, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun RepeatCountCard(
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ПОВТОРЕНИЙ ОТВЕТА", color = AppMutedText, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text("$value", color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            SmallStepperButton(text = "−", onClick = onDecrease)
            Spacer(modifier = Modifier.width(8.dp))
            SmallStepperButton(text = "+", onClick = onIncrease)
        }
    }
}

@Composable
private fun SmallStepperButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0E7FC)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text, color = AppPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SampleJsonCard() {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("Формат JSON", color = AppMutedText, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEDE6F3))) {
                Text(
                    text = "{\n  \"ВГВКМ\": \"13322\",\n  \"ВКРОР\": \"41124\",\n  \"ВКВГТ\": \"32212\"\n}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = AppText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(62.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppPurple,
            disabledContainerColor = Color(0xFFE9E2EF),
        ),
        shape = RoundedCornerShape(32.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    height: androidx.compose.ui.unit.Dp = 62.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppBackground,
            disabledContainerColor = AppBackground,
            contentColor = AppPurple,
            disabledContentColor = Color(0xFFA69AB6),
        ),
        border = BorderStroke(1.dp, Color(0xFFA99AB5)),
        shape = RoundedCornerShape(32.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) AppPurple else Color(0xFFA69AB6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SoftActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) AppErrorBg else Color(0xFFE6DBFA),
        ),
        shape = RoundedCornerShape(26.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (destructive) Color(0xFF6F1E14) else AppText, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = if (destructive) Color(0xFF6F1E14) else AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VoiceAssistantTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = AppPurple,
        secondary = AppPurple,
        background = AppBackground,
        surface = AppSurface,
        onPrimary = Color.White,
        onBackground = AppText,
        onSurface = AppText,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private val AppBackground = Color(0xFFF6F0FA)
private val AppSurface = Color(0xFFEFE9F4)
private val AppTrack = Color(0xFFE2DAEA)
private val AppPurple = Color(0xFF6D54AF)
private val AppSoftPurple = Color(0xFFE2D5FA)
private val AppText = Color(0xFF1D1732)
private val AppMutedText = Color(0xFF5A5472)
private val AppMuted = Color(0xFF8F889B)
private val AppSuccess = Color(0xFF2F8B3B)
private val AppSuccessBg = Color(0xFFCDECC7)
private val AppError = Color(0xFFC83222)
private val AppErrorBg = Color(0xFFF8D7D2)
private val UiRussianLocale: Locale = Locale.forLanguageTag("ru-RU")
