import io.github.bonigarcia.wdm.WebDriverManager
import com.google.gson.GsonBuilder
import org.openqa.selenium.*
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.File
import java.time.*
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.format.DateTimeFormatter
import javax.swing.*

private val RU: Locale = Locale("ru", "RU")

private const val LOGIN_URL: String = "https://www.ultrafit-club.ru/lkomskaya"

// Для ручного теста текущей недели поставь true.
// Для реальной записи на следующую неделю должно быть false.
private const val USE_CURRENT_WEEK_FOR_TEST: Boolean = false

// Для ночного запуска в воскресенье перед 00:00 поставь true.
// Для ручного теста поставь false.
// Для ночного запуска лучше true.
// Для отладки можно false, чтобы браузер оставался открытым.
private const val CLOSE_BROWSER_AFTER_FINISH: Boolean = true

data class Training(
    val day: String,
    val time: String,
    val name: String
)

private data class LaunchConfig(
    val trainings: List<Training>,
    val waitUntilMidnight: Boolean,
    val allowWaitList: Boolean = false
)

private enum class ReservationResult {
    BOOKED,
    ALREADY_BOOKED,
    WAITLIST,
    NOT_FOUND,
    NO_ACTION_BUTTON,
    ERROR,
    UNKNOWN
}

private data class BookingWindow(
    val targetTime: ZonedDateTime,
    val openMonday: LocalDate,
    val bookingMonday: LocalDate,
    val shouldWait: Boolean
)

private data class TrainingCatalog(
    val options: List<String>,
    val siteNames: List<String>
)

private val DEFAULT_TRAININGS = listOf(
    Training("понедельник", "19:20", "Здоровая спина"),
    Training("понедельник", "20:20", "Женское здоровье"),

    Training("вторник", "20:00", "Сила+стретчинг"),

    Training("четверг", "20:00", "Сила+стретчинг"),

    Training("пятница", "19:20", "Здоровая спина"),

    Training("суббота", "09:00", "Силовой класс"),
    Training("суббота", "10:00", "МФР (миофасциальное расслабление)")
)

private val TRAINING_NAME_OPTIONS = listOf(
    "90х60х90 (кардио+сила)",
    "Port de Bras™",
    "Стрип-стретчинг",
    "ZUMBA TONING ™",
    "ZUMBA™",
    "Активное долголетие",
    "Аэройога",
    "Женское здоровье",
    "Здоровая спина",
    "Йога",
    "Микс тренинг (силовая тренировка на верх)",
    "Микс тренинг (силовая тренировка на низ)",
    "Микс тренинг (Степ аэробика)",
    "Микс тренинг (функциональная тренировка)",
    "МФР (миофасциальное расслабление)",
    "Пилатес",
    "Сила+стретчинг",
    "Силовой класс",
    "Стройное тело",
    "Тай бо",
    "Ягодицы 3Д",
)

private data class BotConfig(
    val timezone: String = "Asia/Yekaterinburg",
    val allowWaitList: Boolean = false,
    val trainings: List<Training> = DEFAULT_TRAININGS
)


// =========================
// БАЗОВЫЕ УТИЛИТЫ
// =========================

private fun normalizeText(value: String): String {
    return value
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(RU)
}

private fun requiredEnvironmentVariable(name: String): String {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Не задана переменная окружения $name")
}

private fun safeFileName(value: String): String {
    return value
        .replace(":", "_")
        .replace("+", "_")
        .replace(Regex("[^a-zA-Zа-яА-Я0-9._-]+"), "_")
        .trim('_')
        .take(80)
}

private val gson = GsonBuilder().setPrettyPrinting().create()

private fun canonicalTrainingName(value: String): String = when (normalizeText(value)) {
    "strip-stretch" -> "Стрип-стретчинг"
    "мфр (миофасциальное расслаблениие)" -> "МФР (миофасциальное расслабление)"
    else -> value.trim()
}

private fun loadBotConfig(file: File): BotConfig {
    if (!file.exists()) {
        val defaultConfig = BotConfig()
        saveBotConfig(file, defaultConfig)
        println("Создан файл настроек: ${file.absolutePath}")
        return defaultConfig
    }

    return try {
        gson.fromJson(file.readText(Charsets.UTF_8), BotConfig::class.java).let { loaded ->
            val normalized = loaded.copy(
                trainings = loaded.trainings.map { training ->
                    training.copy(name = canonicalTrainingName(training.name))
                }
            )

            require(normalized.trainings.isNotEmpty()) {
                "В файле ${file.absolutePath} нет тренировок"
            }
            ZoneId.of(normalized.timezone)
            normalized
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Не удалось прочитать ${file.absolutePath}: ${e.message}", e)
    }
}

private fun saveBotConfig(file: File, config: BotConfig) {
    file.parentFile?.mkdirs()
    file.writeText(gson.toJson(config), Charsets.UTF_8)
}

private fun configFileFromArgs(args: Array<String>): File {
    val value = args.firstOrNull { it.startsWith("--config=") }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    return File(value ?: "ultrafit-config.json").absoluteFile
}

private fun createDriver(): WebDriver {
    WebDriverManager.chromedriver().setup()

    val opts = ChromeOptions()

    opts.addArguments(
        "--window-size=1440,900",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--disable-notifications"
    )

    opts.setPageLoadStrategy(PageLoadStrategy.EAGER)

    return ChromeDriver(opts).apply {
        manage().window().maximize()
    }
}

private fun getNextMondayMidnight(zone: ZoneId = ZoneId.of("Asia/Yekaterinburg")): ZonedDateTime {
    val now = ZonedDateTime.now(zone)

    val daysAhead = (DayOfWeek.MONDAY.value - now.dayOfWeek.value + 7) % 7

    var target = now
        .plusDays(daysAhead.toLong())
        .withHour(0)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)

    if (!target.isAfter(now)) {
        target = target.plusDays(7)
    }

    return target
}

/**
 * Защита от ситуации, когда Windows запустил задачу чуть позже 00:00.
 *
 * Если скрипт стартовал в понедельник между 00:00 и 02:00,
 * считаем, что нужная неделя уже началась сегодня.
 */
private fun resolveBookingWindow(zone: ZoneId): BookingWindow {
    val now = ZonedDateTime.now(zone)
    val todayMidnight = now.toLocalDate().atStartOfDay(zone)

    // Если скрипт запустился в понедельник сразу после 00:00,
    // значит запись уже открылась сегодня.
    if (
        now.dayOfWeek == DayOfWeek.MONDAY &&
        !now.isBefore(todayMidnight) &&
        now.isBefore(todayMidnight.plusHours(2))
    ) {
        val openMonday = todayMidnight.toLocalDate()

        return BookingWindow(
            targetTime = todayMidnight,
            openMonday = openMonday,
            bookingMonday = openMonday.plusWeeks(1),
            shouldWait = false
        )
    }

    val nextOpenMonday = getNextMondayMidnight(zone)
    val openMonday = nextOpenMonday.toLocalDate()

    return BookingWindow(
        targetTime = nextOpenMonday,
        openMonday = openMonday,
        bookingMonday = openMonday.plusWeeks(1),
        shouldWait = true
    )
}

private fun sleepUntil(target: ZonedDateTime) {
    val now = ZonedDateTime.now(target.zone)
    val millis = Duration.between(now, target).toMillis()

    if (millis > 0) {
        println("Жду до $target (~${millis / 1000} сек.)")
        Thread.sleep(millis)
    } else {
        println("Время $target уже прошло. Сейчас: $now")
    }
}

private fun dumpPage(driver: WebDriver, name: String) {
    val htmlFile = File("$name.html")
    htmlFile.writeText(driver.pageSource)

    val screenshot = (driver as TakesScreenshot).getScreenshotAs(OutputType.FILE)
    val screenshotFile = File("$name.png")
    screenshot.copyTo(screenshotFile, overwrite = true)

    println("Сохранено: ${htmlFile.absolutePath}")
    println("Сохранено: ${screenshotFile.absolutePath}")
}

private fun scrollTo(driver: WebDriver, element: WebElement) {
    (driver as JavascriptExecutor).executeScript(
        "arguments[0].scrollIntoView({block:'center'});",
        element
    )
}

private fun safeClick(driver: WebDriver, element: WebElement) {
    try {
        element.click()
    } catch (_: ElementClickInterceptedException) {
        (driver as JavascriptExecutor).executeScript("arguments[0].click();", element)
    } catch (_: WebDriverException) {
        (driver as JavascriptExecutor).executeScript("arguments[0].click();", element)
    }
}

private fun clickFreshElement(driver: WebDriver, element: WebElement) {
    try {
        element.click()
    } catch (_: ElementClickInterceptedException) {
        (driver as JavascriptExecutor).executeScript("arguments[0].click();", element)
    } catch (_: WebDriverException) {
        (driver as JavascriptExecutor).executeScript("arguments[0].click();", element)
    }
}

private fun WebElement.safeSet(text: String) {
    this.click()
    this.sendKeys(Keys.chord(Keys.CONTROL, "a"))
    this.sendKeys(Keys.DELETE)
    this.sendKeys(text)
}

// =========================
// ОКНО
// =========================
private class TrainingRow(
    private val parentPanel: JPanel,
    trainingNameOptions: List<String>,
    initialTraining: Training? = null,
    private val onDayChanged: () -> Unit,
    private val onRemove: (TrainingRow) -> Unit
) {
    val panel = JPanel(GridBagLayout())

    private val dayBox = JComboBox(
        arrayOf(
            "понедельник",
            "вторник",
            "среда",
            "четверг",
            "пятница",
            "суббота",
            "воскресенье"
        )
    )

    private val timeField = JTextField(initialTraining?.time ?: "19:00", 6)

    private val trainingBox = JComboBox(trainingNameOptions.toTypedArray()).apply {
        isEditable = true
    }

    init {
        initialTraining?.let { training ->
            dayBox.selectedItem = training.day

            if (!trainingNameOptions.contains(training.name)) {
                trainingBox.addItem(training.name)
            }

            trainingBox.selectedItem = training.name
        }

        trainingBox.toolTipText = "Можно выбрать название из списка или ввести своё"

        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 4, 4, 4)
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridx = 0
        gbc.weightx = 0.22
        panel.add(dayBox, gbc)

        gbc.gridx = 1
        gbc.weightx = 0.12
        panel.add(timeField, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.58
        panel.add(trainingBox, gbc)

        val removeButton = JButton("Удалить")
        removeButton.addActionListener {
            onRemove(this)
        }

        gbc.gridx = 3
        gbc.weightx = 0.08
        panel.add(removeButton, gbc)

        dayBox.addActionListener {
            onDayChanged()
        }
    }

    fun selectedDay(): String = dayBox.selectedItem?.toString()?.trim().orEmpty()

    fun updateTrainingNameOptions(options: List<String>) {
        val selectedName = trainingBox.editor.item?.toString()?.trim().orEmpty()

        trainingBox.removeAllItems()
        options.forEach { trainingBox.addItem(it) }

        if (selectedName.isNotBlank() && options.none { normalizeText(it) == normalizeText(selectedName) }) {
            trainingBox.addItem(selectedName)
        }

        if (selectedName.isNotBlank()) {
            trainingBox.selectedItem = selectedName
        }
    }

    fun toTrainingOrNull(): Training? {
        val day = dayBox.selectedItem?.toString()?.trim().orEmpty()
        val time = timeField.text.trim()
        val name = trainingBox.editor.item?.toString()?.trim().orEmpty()

        if (day.isBlank() || time.isBlank() || name.isBlank()) {
            JOptionPane.showMessageDialog(
                parentPanel,
                "Заполните день, время и название тренировки",
                "Не заполнены данные",
                JOptionPane.WARNING_MESSAGE
            )
            return null
        }

        val timeIsValid = Regex("""^\d{2}:\d{2}$""").matches(time) &&
                runCatching { LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")) }.isSuccess

        if (!timeIsValid) {
            JOptionPane.showMessageDialog(
                parentPanel,
                "Время нужно указать в формате 19:20",
                "Неверное время",
                JOptionPane.WARNING_MESSAGE
            )
            return null
        }

        return Training(day, time, name)
    }
}

private fun showLaunchWindow(
    bookingMonday: LocalDate,
    bookingWindow: BookingWindow,
    initialTrainings: List<Training>,
    initialTrainingCatalog: TrainingCatalog,
    defaultAllowWaitList: Boolean,
    refreshTrainingCatalog: () -> TrainingCatalog
): LaunchConfig? {
    var result: LaunchConfig? = null
    var currentCatalog = initialTrainingCatalog

    val frame = JFrame("Ultrafit Bot")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.minimumSize = Dimension(920, 700)
    frame.setLocationRelativeTo(null)

    val mainPanel = JPanel(BorderLayout(12, 12))
    mainPanel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

    val title = JLabel(
        """
        <html>
        <h2 style='margin-bottom:4px'>Запись в Ultrafit</h2>
        <div>Неделя записи: <b>${bookingMonday.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${
            bookingMonday.plusDays(
                6
            ).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        }</b></div>
        </html>
        """.trimIndent()
    )

    val countdownLabel = JLabel()
    countdownLabel.font = countdownLabel.font.deriveFont(Font.BOLD, 17f)
    countdownLabel.foreground = Color(20, 92, 48)

    fun updateCountdown() {
        val now = ZonedDateTime.now(bookingWindow.targetTime.zone)
        val remaining = Duration.between(now, bookingWindow.targetTime)

        countdownLabel.text = if (remaining.isNegative || remaining.isZero) {
            "Запись уже открыта — можно запускать сейчас"
        } else {
            val totalSeconds = remaining.seconds
            val days = totalSeconds / 86_400
            val hours = (totalSeconds % 86_400) / 3_600
            val minutes = (totalSeconds % 3_600) / 60
            val seconds = totalSeconds % 60
            val dayPart = if (days > 0) "$days дн. " else ""
            "До открытия записи: $dayPart${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        }
    }

    val countdownPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = Color(235, 247, 239)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(190, 220, 199)),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)
        )
        add(countdownLabel, BorderLayout.CENTER)
    }

    val topPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(title)
        add(Box.createVerticalStrut(10))
        add(countdownPanel)
    }

    mainPanel.add(topPanel, BorderLayout.NORTH)

    updateCountdown()
    val countdownTimer = Timer(1000) { updateCountdown() }.apply { start() }

    val rowsPanel = JPanel()
    rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)
    rowsPanel.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

    val rows = mutableListOf<TrainingRow>()
    val dayOrder = listOf(
        "понедельник",
        "вторник",
        "среда",
        "четверг",
        "пятница",
        "суббота",
        "воскресенье"
    )

    fun refreshRowsPanel() {
        rowsPanel.removeAll()

        rows.groupBy { it.selectedDay() }
            .toList()
            .sortedBy { (day, _) ->
                dayOrder.indexOf(day).let { if (it < 0) Int.MAX_VALUE else it }
            }
            .forEach { (day, dayRows) ->
                val dayTitle = day.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(RU) else it.toString()
                }
                val dayLabel = JLabel("$dayTitle  •  ${dayRows.size}")
                dayLabel.font = dayLabel.font.deriveFont(Font.BOLD)
                dayLabel.foreground = Color(65, 65, 65)
                dayLabel.border = BorderFactory.createEmptyBorder(10, 4, 2, 4)
                rowsPanel.add(dayLabel)
                dayRows.forEach { rowsPanel.add(it.panel) }
            }

        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    fun addRow(initialTraining: Training? = null) {
        val row = TrainingRow(
            parentPanel = rowsPanel,
            trainingNameOptions = currentCatalog.options,
            initialTraining = initialTraining,
            onDayChanged = { refreshRowsPanel() },
            onRemove = { rowToRemove ->
                rows.remove(rowToRemove)
                refreshRowsPanel()
            }
        )

        rows.add(row)
        refreshRowsPanel()
    }

    initialTrainings.forEach { training ->
        addRow(training)
    }

    val centerPanel = JPanel(BorderLayout(8, 8))
    centerPanel.border = BorderFactory.createTitledBorder("Тренировки на неделю")

    val headerPanel = JPanel(GridBagLayout())
    val headerGbc = GridBagConstraints()
    headerGbc.insets = Insets(4, 4, 4, 4)
    headerGbc.gridy = 0
    headerGbc.fill = GridBagConstraints.HORIZONTAL

    headerGbc.gridx = 0
    headerGbc.weightx = 0.22
    headerPanel.add(JLabel("День недели"), headerGbc)

    headerGbc.gridx = 1
    headerGbc.weightx = 0.12
    headerPanel.add(JLabel("Время"), headerGbc)

    headerGbc.gridx = 2
    headerGbc.weightx = 0.58
    headerPanel.add(JLabel("Тренировка"), headerGbc)

    headerGbc.gridx = 3
    headerGbc.weightx = 0.08
    headerPanel.add(JLabel(""), headerGbc)

    centerPanel.add(headerPanel, BorderLayout.NORTH)
    centerPanel.add(JScrollPane(rowsPanel), BorderLayout.CENTER)

    val addButton = JButton("+ Добавить тренировку")
    addButton.addActionListener {
        addRow()
    }

    val catalogStatusLabel = JLabel()
    fun updateCatalogStatus(prefix: String = "Список обновлён") {
        catalogStatusLabel.text = if (currentCatalog.siteNames.isEmpty()) {
            "Названия с сайта получить не удалось — доступны сохранённые варианты"
        } else {
            "$prefix: с сайта получено ${currentCatalog.siteNames.size} названий"
        }
    }
    updateCatalogStatus("Актуальный список")

    val refreshNamesButton = JButton("Обновить названия с Ultrafit")
    refreshNamesButton.addActionListener {
        refreshNamesButton.isEnabled = false
        catalogStatusLabel.text = "Обновляю названия…"

        object : SwingWorker<TrainingCatalog, Unit>() {
            override fun doInBackground(): TrainingCatalog = refreshTrainingCatalog()

            override fun done() {
                try {
                    currentCatalog = get()
                    rows.forEach { it.updateTrainingNameOptions(currentCatalog.options) }
                    updateCatalogStatus()
                } catch (e: Exception) {
                    catalogStatusLabel.text = "Не удалось обновить названия"
                    JOptionPane.showMessageDialog(
                        frame,
                        "Не удалось обновить список с Ultrafit: ${e.cause?.message ?: e.message}",
                        "Ошибка обновления",
                        JOptionPane.WARNING_MESSAGE
                    )
                } finally {
                    refreshNamesButton.isEnabled = true
                }
            }
        }.execute()
    }

    val addPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    addPanel.add(addButton)
    addPanel.add(refreshNamesButton)
    addPanel.add(catalogStatusLabel)

    centerPanel.add(addPanel, BorderLayout.SOUTH)

    mainPanel.add(centerPanel, BorderLayout.CENTER)

    val bottomPanel = JPanel(BorderLayout(8, 8))

    val allowWaitListBox = JCheckBox("Записывать в лист ожидания", defaultAllowWaitList)

    val runAtMidnightButton = JButton("Запланировать на 00:00").apply {
        background = Color(25, 125, 62)
        foreground = Color.WHITE
        font = font.deriveFont(Font.BOLD)
        isFocusPainted = false
        toolTipText = "Программа будет ждать открытия записи и начнёт в 00:00"
    }
    val runNowButton = JButton("Запустить сейчас")
    runNowButton.toolTipText = "Начать попытку записи немедленно"
    val checkButton = JButton("Проверить без записи")
    val updateButton = JButton("Обновить программу")
    updateButton.toolTipText = "Проверить наличие новой версии на GitHub"
    val cancelButton = JButton("Отмена")

    fun collectTrainings(): List<Training>? {
        val trainings = mutableListOf<Training>()

        for (row in rows) {
            val training = row.toTrainingOrNull() ?: return null
            trainings.add(training)
        }

        val distinctTrainings = trainings.distinctBy {
            "${normalizeText(it.day)}|${normalizeText(it.time)}|${normalizeText(it.name)}"
        }

        if (distinctTrainings.isEmpty()) {
            JOptionPane.showMessageDialog(
                frame,
                "Добавьте хотя бы одну тренировку",
                "Нет тренировок",
                JOptionPane.WARNING_MESSAGE
            )
            return null
        }

        return distinctTrainings
    }

    fun unknownSiteNames(trainings: List<Training>): List<String> {
        val siteNames = currentCatalog.siteNames.map(::normalizeText).toSet()
        if (siteNames.isEmpty()) {
            return emptyList()
        }

        return trainings
            .map { it.name }
            .filter { normalizeText(it) !in siteNames }
            .distinctBy(::normalizeText)
    }

    fun confirmPreflight(trainings: List<Training>): Boolean {
        if (currentCatalog.siteNames.isEmpty()) {
            return JOptionPane.showConfirmDialog(
                frame,
                "Не удалось получить актуальный список названий с сайта. Продолжить с сохранёнными названиями?",
                "Список Ultrafit недоступен",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        }

        val unknownNames = unknownSiteNames(trainings)
        if (unknownNames.isEmpty()) {
            return true
        }

        return JOptionPane.showConfirmDialog(
            frame,
            "В текущем списке Ultrafit не найдены:\n\n${unknownNames.joinToString("\n") { "• $it" }}\n\nНазвание можно оставить вручную, но проверьте написание. Продолжить?",
            "Проверьте названия",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
    }

    checkButton.addActionListener {
        val trainings = collectTrainings() ?: return@addActionListener
        val unknownNames = unknownSiteNames(trainings)

        val message = when {
            currentCatalog.siteNames.isEmpty() ->
                "День, время и заполнение строк проверены.\nАктуальный список названий с сайта сейчас недоступен."

            unknownNames.isNotEmpty() ->
                "Проверьте написание этих названий:\n\n${unknownNames.joinToString("\n") { "• $it" }}"

            else ->
                "Проверка пройдена: все ${trainings.size} занятий заполнены, а названия найдены в списке Ultrafit.\n\nЗапись при этой проверке не выполнялась."
        }

        JOptionPane.showMessageDialog(
            frame,
            message,
            "Проверка без записи",
            if (unknownNames.isEmpty()) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
        )
    }

    updateButton.addActionListener {
        updateButton.isEnabled = false
        updateButton.text = "Проверяю и скачиваю…"

        object : SwingWorker<PreparedUpdate?, Unit>() {
            override fun doInBackground(): PreparedUpdate? {
                val update = AppUpdater.findUpdate() ?: return null
                return AppUpdater.downloadAndPrepare(update)
            }

            override fun done() {
                try {
                    val preparedUpdate = get()
                    if (preparedUpdate == null) {
                        JOptionPane.showMessageDialog(
                            frame,
                            "Установлена актуальная версия ${AppUpdater.currentVersion}.",
                            "Обновление не требуется",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                        return
                    }

                    JOptionPane.showMessageDialog(
                        frame,
                        "Версия ${preparedUpdate.version} скачана и проверена.\n\n" +
                            "Сейчас программа закроется, установит обновление и запустится снова.",
                        "Обновление готово",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                    AppUpdater.launchPreparedUpdate(preparedUpdate)
                    frame.dispose()
                } catch (e: Exception) {
                    val reason = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                    JOptionPane.showMessageDialog(
                        frame,
                        "Не удалось обновить программу:\n$reason",
                        "Ошибка обновления",
                        JOptionPane.ERROR_MESSAGE
                    )
                } finally {
                    updateButton.isEnabled = true
                    updateButton.text = "Обновить программу"
                }
            }
        }.execute()
    }

    runAtMidnightButton.addActionListener {
        val trainings = collectTrainings() ?: return@addActionListener
        if (!confirmPreflight(trainings)) return@addActionListener

        result = LaunchConfig(
            trainings = trainings,
            waitUntilMidnight = true,
            allowWaitList = allowWaitListBox.isSelected
        )

        frame.dispose()
    }

    runNowButton.addActionListener {
        val trainings = collectTrainings() ?: return@addActionListener
        if (!confirmPreflight(trainings)) return@addActionListener

        result = LaunchConfig(
            trainings = trainings,
            waitUntilMidnight = false,
            allowWaitList = allowWaitListBox.isSelected
        )

        frame.dispose()
    }

    cancelButton.addActionListener {
        result = null
        frame.dispose()
    }

    val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(allowWaitListBox)
        add(JLabel("Версия: ${AppUpdater.currentVersion}"))
    }
    val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(updateButton)
        add(checkButton)
        add(runNowButton)
        add(cancelButton)
        add(runAtMidnightButton)
    }

    bottomPanel.add(optionsPanel, BorderLayout.WEST)
    bottomPanel.add(actionsPanel, BorderLayout.EAST)

    mainPanel.add(bottomPanel, BorderLayout.SOUTH)

    frame.contentPane = mainPanel
    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.rootPane.defaultButton = runAtMidnightButton

    val latch = java.util.concurrent.CountDownLatch(1)

    frame.addWindowListener(
        object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                countdownTimer.stop()
                latch.countDown()
            }
        }
    )

    frame.isVisible = true
    latch.await()

    return result
}

// =========================
// ЛОГИН
// =========================

private fun login(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(30))
    val login = requiredEnvironmentVariable("ULTRAFIT_LOGIN")
    val password = requiredEnvironmentVariable("ULTRAFIT_PASSWORD")

    driver.get(LOGIN_URL)

    val cabinet = wait.until(
        ExpectedConditions.visibilityOfElementLocated(
            By.cssSelector("div[data-fit-cabinet], #fit-cabinet")
        )
    )

    val phoneInput = wait.until {
        cabinet.findElements(
            By.cssSelector("input[name='phone'], input[type='tel'], input[type='text']")
        ).firstOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
    }!!

    val passInput = wait.until {
        cabinet.findElements(
            By.cssSelector("input[name='password'], input[name='pass'], input[type='password']")
        ).firstOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
    }!!

    val submitBtn = wait.until {
        cabinet.findElements(By.cssSelector("button[type='submit']"))
            .firstOrNull { el ->
                try {
                    el.isDisplayed && el.isEnabled && normalizeText(el.text) == "войти"
                } catch (_: StaleElementReferenceException) {
                    false
                }
            }
    }!!

    phoneInput.safeSet(login)
    passInput.safeSet(password)

    scrollTo(driver, submitBtn)
    safeClick(driver, submitBtn)

    wait.until {
        val loginFormVisible = driver.findElements(By.cssSelector("form[data-form-auth]"))
            .any { el ->
                try {
                    el.isDisplayed
                } catch (_: StaleElementReferenceException) {
                    false
                }
            }

        !loginFormVisible
    }

    wait.until(
        ExpectedConditions.or(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".head-user_fit")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".menu-h-fit")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".list-catalog_fit")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-get-user-schedule]")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-body-schedule]")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-fit1c-calendar]"))
        )
    )

    println("Логин выполнен, ЛК открыт")
}

// =========================
// ОЖИДАНИЕ ЛК
// =========================

private fun waitCabinetLoaded(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(60))

    wait.until(
        ExpectedConditions.presenceOfElementLocated(
            By.cssSelector("#fit-cabinet, div[data-fit-cabinet]")
        )
    )

    wait.until { d ->
        val skeletons = d.findElements(By.cssSelector(".skeleton-container, .cssload-loader"))

        skeletons.none { el ->
            try {
                el.isDisplayed
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
    }

    wait.until(
        ExpectedConditions.or(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".menu-h-fit")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-get-user-schedule]")),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-body-schedule]"))
        )
    )

    println("Личный кабинет полностью загрузился")
}

// =========================
// ОТКРЫТИЕ РАСПИСАНИЯ
// =========================

private fun openSchedulePage(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(40))

    waitCabinetLoaded(driver)

    wait.until(
        ExpectedConditions.presenceOfElementLocated(
            By.cssSelector("#fit-cabinet, div[data-fit-cabinet]")
        )
    )

    val alreadyOpened = driver.findElements(
        By.cssSelector("[data-body-schedule], [data-fit1c-calendar] .body-schedule")
    ).any { el ->
        try {
            el.isDisplayed
        } catch (_: Exception) {
            false
        }
    } || driver.findElements(
        By.xpath("//div[contains(@class,'menu-h-fit')]//span[normalize-space(.)='Расписание']")
    ).any { el ->
        try {
            el.isDisplayed
        } catch (_: Exception) {
            false
        }
    }

    if (alreadyOpened) {
        waitSchedulePageReady(driver)
        println("Страница 'Расписание' уже открыта")
        return
    }

    val scheduleLink = wait.until { d ->
        val links = d.findElements(
            By.cssSelector("[data-get-user-schedule], [data-page='schedule']")
        ) + d.findElements(
                    By.xpath("//div[contains(@class,'menu-h-fit')]//a[normalize-space(.)='Расписание']")
                )
        links.firstOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
    }!!

    scrollTo(driver, scheduleLink)
    safeClick(driver, scheduleLink)

    waitSchedulePageReady(driver)

    println("Страница 'Расписание' открыта")
}

private fun waitSchedulePageReady(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(40))

    wait.until { d ->
        val activeScheduleTab = d.findElements(
            By.xpath("//div[contains(@class,'menu-h-fit')]//span[normalize-space(.)='Расписание']")
        ).any { el ->
            try {
                el.isDisplayed
            } catch (_: Exception) {
                false
            }
        }

        val hasScheduleContent = d.findElements(
            By.cssSelector(
                ".schedule_fit, .choice-schedule_fit, .date-schedule_fit, .box-schedule_fit, .item-konju_fit, " +
                        "[data-body-schedule], [data-slider-date], [data-timetable-item]"
            )
        ).any { el ->
            try {
                el.text.trim().isNotBlank() || el.isDisplayed
            } catch (_: Exception) {
                false
            }
        }

        activeScheduleTab || hasScheduleContent
    }
}

// =========================
// РЕЖИМ "ДЕНЬ"
// =========================

private fun clickDayMode(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(25))

    fun dayModeIsReady(d: WebDriver): Boolean {
        val activeDay = d.findElements(By.cssSelector("[data-show-type='day'].active")).isNotEmpty() ||
                d.findElements(
                    By.xpath("//div[contains(@class,'choice-schedule_fit')]//*[contains(@class,'active_fit')]//a[normalize-space(.)='день']")
                ).isNotEmpty()

        val hasDayContent = d.findElements(
            By.cssSelector(
                "select[data-select-day] option:not([value='week']), " +
                        "[data-body-schedule] [data-table], .date-schedule_fit"
            )
        ).isNotEmpty()

        return activeDay && hasDayContent
    }

    if (dayModeIsReady(driver)) {
        println("Режим 'день' уже активен")
        return
    }

    val root = wait.until(
        ExpectedConditions.presenceOfElementLocated(
            By.cssSelector("#fit-cabinet, div[data-fit-cabinet]")
        )
    )

    val dayLink = wait.until {
        val links = root.findElements(By.cssSelector("[data-show-type='day']")) +
                root.findElements(
                    By.xpath(".//div[contains(@class,'choice-schedule_fit')]//a[normalize-space(.)='день']")
                )
        links.firstOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
    }!!

    scrollTo(driver, dayLink)
    safeClick(driver, dayLink)

    wait.until { d -> dayModeIsReady(d) }

    println("Режим 'день' активирован")
}

// =========================
// ДАТЫ
// =========================

private fun resolveTrainingDate(bookingMonday: LocalDate, dayName: String): LocalDate {
    val dayIndex = when (normalizeText(dayName)) {
        "понедельник" -> 0
        "вторник" -> 1
        "среда" -> 2
        "четверг" -> 3
        "пятница" -> 4
        "суббота" -> 5
        "воскресенье" -> 6
        else -> error("Неизвестный день недели: $dayName")
    }

    return bookingMonday.plusDays(dayIndex.toLong())
}

private fun buildCabinetDateText(bookingMonday: LocalDate, dayName: String): String {
    val date = resolveTrainingDate(bookingMonday, dayName)

    val shortDay = date.dayOfWeek
        .getDisplayName(TextStyle.SHORT, RU)
        .replace(".", "")
        .lowercase(RU)

    return "${date.dayOfMonth} $shortDay"
}

private fun visibleDateElements(driver: WebDriver): List<WebElement> {
    val v2Dates = driver.findElements(By.cssSelector("[data-mobile-day] [data-date]"))
    val v4Dates = driver.findElements(By.cssSelector(".date-schedule_fit"))

    return (v2Dates + v4Dates)
        .filter { el ->
            try {
                el.isDisplayed && el.text.trim().isNotBlank()
            } catch (_: StaleElementReferenceException) {
                false
            }
        }
}

private fun visibleDatesSignature(driver: WebDriver): String {
    return visibleDateElements(driver)
        .joinToString("|") { element ->
            element.getAttribute("data-date")?.takeIf { it.isNotBlank() }
                ?: normalizeText(element.text)
        }
}

private fun openDateInSchedule(
    driver: WebDriver,
    bookingMonday: LocalDate,
    dayName: String
) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(30))
    val targetDate = resolveTrainingDate(bookingMonday, dayName)
    val targetDateText = buildCabinetDateText(bookingMonday, dayName)
    val targetDateKey = targetDate.format(DateTimeFormatter.ofPattern("dd-MM-yy"))
    val targetDateDisplay = targetDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    val targetDateIso = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

    println("Ищу дату в расписании: $targetDateText ($targetDateKey)")

    val v2DaySelect = driver.findElements(By.cssSelector("select[data-select-day]"))
        .firstOrNull()

    if (v2DaySelect != null) {
        val targetOption = v2DaySelect.findElements(By.tagName("option"))
            .firstOrNull { option ->
                val optionText = option.getAttribute("textContent") ?: option.text
                optionText.contains(targetDateDisplay)
            }

        if (targetOption != null) {
            val targetValue = targetOption.getAttribute("value")
            val currentValue = v2DaySelect.getAttribute("value")

            if (currentValue != targetValue) {
                (driver as JavascriptExecutor).executeScript(
                    """
                    const select = arguments[0];
                    const value = arguments[1];
                    if (window.jQuery) {
                        window.jQuery(select).val(value).trigger('change');
                    } else {
                        select.value = value;
                        select.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    """.trimIndent(),
                    v2DaySelect,
                    targetValue
                )
            }

            wait.until { d ->
                val select = d.findElements(By.cssSelector("select[data-select-day]"))
                    .firstOrNull() ?: return@until false
                val selected = select.getAttribute("value") == targetValue
                val ajaxIdle = try {
                    (d as JavascriptExecutor).executeScript(
                        "return !window.jQuery || window.jQuery.active === 0;"
                    ) as Boolean
                } catch (_: Exception) {
                    true
                }
                val items = d.findElements(By.cssSelector(".active-slide [data-timetable-item]"))
                val scheduleMatchesDate = items.isEmpty() || items.any { item ->
                    item.getAttribute("data-options")?.contains(targetDateIso) == true
                }

                selected && ajaxIdle && scheduleMatchesDate
            }

            println("Дата открыта через список: $targetDateDisplay")
            return
        }
    }

    repeat(10) { attempt ->
        val dates = visibleDateElements(driver)

        println("Видимые даты: ${dates.joinToString(", ") { normalizeText(it.text) }}")

        val target = dates.firstOrNull { el ->
            el.getAttribute("data-date") == targetDateKey || normalizeText(el.text) == targetDateText
        }

        if (target != null) {
            val before = driver.findElement(
                By.cssSelector("#fit-cabinet, div[data-fit-cabinet]")
            ).text

            scrollTo(driver, target)
            safeClick(driver, target)

            wait.until { d ->
                val active = d.findElements(
                    By.cssSelector("[data-mobile-day] [data-date].active, .date-schedule_fit.active_fit")
                )
                    .any { el ->
                        try {
                            el.getAttribute("data-date") == targetDateKey ||
                                    normalizeText(el.text) == targetDateText
                        } catch (_: StaleElementReferenceException) {
                            false
                        }
                    }

                val changed = try {
                    d.findElement(By.cssSelector("#fit-cabinet, div[data-fit-cabinet]")).text != before
                } catch (_: Exception) {
                    true
                }

                active || changed
            }

            println("Дата открыта: $targetDateText")
            return
        }

        val beforeDates = visibleDatesSignature(driver)

        val nextArrow = driver.findElements(
            By.cssSelector(
                ".slider-date .bx-next:not(.disabled), [data-slider-next], " +
                        ".body-schedule .bx-next:not(.disabled), " +
                        ".arrow-schedule-next_fit, .wr-next-schedule_fit"
            )
        ).firstOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: StaleElementReferenceException) {
                false
            }
        }

        if (nextArrow == null) {
            throw RuntimeException("Не нашёл стрелку следующей недели/дат")
        }

        println("Дата '$targetDateText' не найдена, листаю вперёд. Попытка ${attempt + 1}")

        safeClick(driver, nextArrow)

        wait.until { d ->
            visibleDatesSignature(d) != beforeDates
        }
    }

    throw RuntimeException("Не удалось найти дату '$targetDateText' в расписании")
}

// =========================
// ЗАНЯТИЯ
// =========================

private fun waitScheduleItemsLoaded(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(30))

    wait.until { d ->
        val items = d.findElements(
            By.cssSelector(
                ".swiper-slide-active .box-schedule_fit a, " +
                        ".swiper-slide-active .item-konju_fit a, " +
                        ".box-schedule_fit a, " +
                        ".item-konju_fit a, " +
                        ".active-slide [data-timetable-item], " +
                        ".active-slide td.active [data-timetable-item], " +
                        ".active-slide td:not(.none-td) [data-timetable-item]"
            )
        )

        val hasTrainingItems = items.any { el ->
            try {
                val text = normalizeText(el.text)
                text.isNotBlank() && Regex("""\b\d{2}:\d{2}\b""").containsMatchIn(text)
            } catch (_: StaleElementReferenceException) {
                false
            }
        }

        val hasEmptyText = d.findElements(
            By.xpath("//*[contains(., 'Нет занятий') or contains(., 'ничего не найдено')]")
        ).any { el ->
            try {
                el.isDisplayed
            } catch (_: Exception) {
                false
            }
        }

        val v2TableReady = d.findElements(By.cssSelector("[data-body-schedule] [data-table]"))
            .any { el ->
                try {
                    el.isDisplayed
                } catch (_: Exception) {
                    false
                }
            } && d.findElements(By.cssSelector(".cssload-loader"))
            .none { it.isDisplayed }

        hasTrainingItems || hasEmptyText || v2TableReady
    }

    println("Занятия для выбранной даты загружены")
}

private fun getScheduleItems(driver: WebDriver): List<WebElement> {
    val v2ActiveItems = driver.findElements(
        By.cssSelector(".active-slide [data-timetable-item]")
    )

    val activeSlideItems = driver.findElements(
        By.cssSelector(
            ".swiper-slide-active .box-schedule_fit a, " +
                    ".swiper-slide-active .item-konju_fit a"
        )
    )

    val fallbackItems = driver.findElements(
        By.cssSelector(
            ".box-schedule_fit a, " +
                    ".item-konju_fit a, " +
                    ".active-slide [data-timetable-item]"
        )
    )

    val items = when {
        v2ActiveItems.isNotEmpty() -> v2ActiveItems
        activeSlideItems.isNotEmpty() -> activeSlideItems
        else -> fallbackItems
    }

    println("Всего занятий в DOM: ${items.size}")

    return items.filter { el ->
        try {
            el.text.trim().isNotBlank()
        } catch (_: StaleElementReferenceException) {
            false
        }
    }
}

private fun discoverTrainingNames(driver: WebDriver): TrainingCatalog {
    val discovered = linkedSetOf<String>()

    try {
        println("Собираю актуальные названия тренировок из расписания")

        driver.findElements(By.cssSelector("[data-calendar-filter] [data-service]"))
            .map { (it.getAttribute("textContent") ?: it.text).trim() }
            .filter { it.isNotBlank() }
            .forEach { discovered.add(it) }

        if (discovered.isNotEmpty()) {
            println("Названия загружены из фильтра расписания: ${discovered.size}")
        } else {
            clickDayMode(driver)

            val dateTexts = visibleDateElements(driver)
                .map { normalizeText(it.text) }
                .filter { it.isNotBlank() }
                .distinct()

            for (dateText in dateTexts) {
                val dateElement = visibleDateElements(driver)
                    .firstOrNull { normalizeText(it.text) == dateText }
                    ?: continue

                scrollTo(driver, dateElement)
                safeClick(driver, dateElement)

                try {
                    WebDriverWait(driver, Duration.ofSeconds(10)).until { d ->
                        d.findElements(
                            By.cssSelector(".date-schedule_fit.active_fit, [data-mobile-day] [data-date].active")
                        ).any { normalizeText(it.text) == dateText }
                    }
                    waitScheduleItemsLoaded(driver)
                } catch (e: Exception) {
                    println("Не удалось прочитать занятия за $dateText: ${e.message}")
                    continue
                }

                getScheduleItems(driver).forEach { item ->
                    val name = try {
                        item.findElement(By.cssSelector(".name-occupat_fit, .tit-beidj_rz")).text.trim()
                    } catch (_: Exception) {
                        ""
                    }

                    if (name.isNotBlank()) {
                        discovered.add(name)
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Автозагрузка названий не удалась: ${e.message}")
    }

    val siteNames = discovered
        .distinctBy { normalizeText(it) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    val options = (TRAINING_NAME_OPTIONS + siteNames)
        .distinctBy { normalizeText(it) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    println("Для выбора доступно названий: ${options.size}")
    return TrainingCatalog(options = options, siteNames = siteNames)
}

private fun openTrainingCardOnly(
    driver: WebDriver,
    training: Training
): Boolean {
    val expectedTime = normalizeText(training.time)
    val expectedName = normalizeText(training.name)

    val items = getScheduleItems(driver)

    println("Найдено занятий для проверки: ${items.size}")

    for (item in items) {
        val timeText = try {
            normalizeText(item.findElement(By.cssSelector(".time-box-schedule_fit, .tm-s")).text)
        } catch (_: Exception) {
            normalizeText(item.text)
        }

        val nameText = try {
            normalizeText(item.findElement(By.cssSelector(".name-occupat_fit, .tit-beidj_rz")).text)
        } catch (_: Exception) {
            normalizeText(item.text)
        }

        val fullText = normalizeText(item.text)

        println("Проверяю: time='$timeText', name='$nameText', full='$fullText'")

        val timeMatches = timeText.contains(expectedTime) || fullText.contains(expectedTime)
        val nameMatches = nameText == expectedName || fullText.contains(expectedName)

        if (timeMatches && nameMatches) {
            println("Найдено нужное занятие:")
            println(item.text)

            scrollTo(driver, item)
            safeClick(driver, item)

            waitTrainingPopupLoaded(driver, training)

            println("Карточка занятия открыта")
            return true
        }
    }

    println("Не найдено занятие: ${training.name} @ ${training.time}")
    return false
}

// =========================
// КАРТОЧКА И ЗАПИСЬ
// =========================

private const val VISIBLE_FANCYBOX_SELECTOR =
    ":is(.fancybox__container, .fancyboxFit__container):not([aria-hidden='true'])"

private const val TRAINING_POPUP_SELECTOR =
    "$VISIBLE_FANCYBOX_SELECTOR .popup_fit, " +
            "$VISIBLE_FANCYBOX_SELECTOR [data-set-app-form]"

private const val TRAINING_ACTION_SELECTOR =
    "[data-app-set], [data-app-wait], [data-app-wait-record-list], " +
            ".button_fit a, .button_fit button, .button_rz a, .button_rz button"

private fun hasTrainingAction(root: WebElement): Boolean =
    root.findElements(By.cssSelector(TRAINING_ACTION_SELECTOR)).any { button ->
        try {
            val text = normalizeText(button.text)
            button.isDisplayed &&
                    button.isEnabled &&
                    (text.contains("запис") ||
                            text.contains("лист ожидания") ||
                            text.contains("отмен"))
        } catch (_: Exception) {
            false
        }
    }

private fun waitTrainingPopupLoaded(
    driver: WebDriver,
    training: Training
): WebElement {
    val wait = WebDriverWait(driver, Duration.ofSeconds(30))

    val expectedName = normalizeText(training.name)
    val expectedTime = normalizeText(training.time)

    val popup = wait.until { d ->
        d.findElements(By.cssSelector(TRAINING_POPUP_SELECTOR)).firstOrNull { el ->
            try {
                val text = normalizeText(el.text)

                el.isDisplayed &&
                        text.contains(expectedName) &&
                        text.contains(expectedTime) &&
                        hasTrainingAction(el)
            } catch (_: Exception) {
                false
            }
        }
    }!!

    println("Карточка занятия полностью загружена")
    println(popup.text.take(700))

    return popup
}

private fun findFreshActionButton(
    driver: WebDriver,
    training: Training
): WebElement {
    val wait = WebDriverWait(driver, Duration.ofSeconds(20))

    val expectedName = normalizeText(training.name)
    val expectedTime = normalizeText(training.time)

    return wait.until { d ->
        val popups = d.findElements(By.cssSelector(TRAINING_POPUP_SELECTOR))

        val trainingPopup = popups.firstOrNull { popup ->
            try {
                val text = normalizeText(popup.text)

                popup.isDisplayed &&
                        text.contains(expectedName) &&
                        text.contains(expectedTime)
            } catch (_: StaleElementReferenceException) {
                false
            }
        }

        trainingPopup
            ?.findElements(By.cssSelector(TRAINING_ACTION_SELECTOR))
            ?.filter { btn ->
                try {
                    val text = normalizeText(btn.text)
                    btn.isDisplayed &&
                            btn.isEnabled &&
                            (text.contains("запис") ||
                                    text.contains("лист ожидания") ||
                                    text.contains("отмен"))
                } catch (_: StaleElementReferenceException) {
                    false
                }
            }
            ?.minByOrNull { btn ->
                val text = normalizeText(btn.text)
                when {
                    text.contains("отмен") -> 0
                    text.contains("лист ожидания") -> 2
                    else -> 1
                }
            }
    }!!
}

private fun waitReservationResult(driver: WebDriver): ReservationResult {
    val wait = WebDriverWait(driver, Duration.ofSeconds(30))

    return wait.until { d ->
        val popups = d.findElements(By.cssSelector(VISIBLE_FANCYBOX_SELECTOR)).filter { el ->
            try {
                el.isDisplayed && el.text.trim().isNotBlank()
            } catch (_: Exception) {
                false
            }
        }

        val texts = popups.map { normalizeText(it.text) }

        texts.forEach { text ->
            println("Текст popup после записи: $text")
        }

        when {
            texts.any {
                it.contains("успешно запис") ||
                        it.contains("вы записаны") ||
                        it.contains("запись подтверждена")
            } -> ReservationResult.BOOKED

            texts.any { it.contains("уже запис") } -> ReservationResult.ALREADY_BOOKED

            texts.any {
                it.contains("лист ожидания") ||
                        it.contains("список ожидания")
            } -> ReservationResult.WAITLIST

            texts.any {
                it.contains("отмен") &&
                        it.contains("запис") &&
                        !it.contains("лист ожидания") &&
                        !it.contains("список ожидания")
            } -> ReservationResult.BOOKED

            texts.any {
                it.contains("ошибка") ||
                        it.contains("невозможно") ||
                        it.contains("нельзя") ||
                        it.contains("нет мест") ||
                        it.contains("запись закрыта")
            } -> ReservationResult.ERROR

            else -> null
        }
    } ?: ReservationResult.UNKNOWN
}

private fun reserveOpenedTraining(
    driver: WebDriver,
    training: Training,
    allowWaitList: Boolean = false
): ReservationResult {
    repeat(5) { attempt ->
        try {
            val actionButton = findFreshActionButton(driver, training)
            val buttonText = normalizeText(actionButton.text)

            println("Кнопка в карточке: '${actionButton.text}'")

            if (buttonText.contains("отмен") && buttonText.contains("лист ожидания")) {
                println("Уже добавлены в лист ожидания. Кнопку отмены не нажимаю.")
                return ReservationResult.WAITLIST
            }

            if (buttonText.contains("отмен")) {
                println("Уже записаны на занятие. Ничего не нажимаю.")
                return ReservationResult.ALREADY_BOOKED
            }

            if (buttonText.contains("лист ожидания") && !allowWaitList) {
                println("Доступен только лист ожидания. allowWaitList=false, не записываюсь.")
                return ReservationResult.WAITLIST
            }

            val canClick =
                buttonText.contains("записаться") ||
                        buttonText.contains("запись") ||
                        buttonText.contains("лист ожидания")

            if (!canClick) {
                println("Неизвестная кнопка действия: '${actionButton.text}'")
                return ReservationResult.UNKNOWN
            }

            println("Нажимаю кнопку действия. Попытка ${attempt + 1}")

            clickFreshElement(driver, actionButton)

            val result = waitReservationResult(driver)

            println("Результат после клика: $result")

            if (result == ReservationResult.BOOKED || result == ReservationResult.WAITLIST) {
                try {
                    closeTopPopup(driver)
                } catch (e: Exception) {
                    println("Не удалось закрыть popup результата: ${e.message}")
                }
            }

            return result
        } catch (e: StaleElementReferenceException) {
            println("Элемент устарел при попытке записи. Повторяю поиск кнопки. Попытка ${attempt + 1}")
            Thread.sleep(300)
        }
    }

    println("Не удалось нажать кнопку записи: элемент постоянно устаревает")
    return ReservationResult.UNKNOWN
}

private fun closeTopPopup(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(10))

    val closeBtn = wait.until { d ->
        d.findElements(
            By.cssSelector(
                "$VISIBLE_FANCYBOX_SELECTOR [data-fancybox-close], " +
                        "$VISIBLE_FANCYBOX_SELECTOR .f-button.is-close, " +
                        "$VISIBLE_FANCYBOX_SELECTOR .carousel__button.is-close, " +
                        "$VISIBLE_FANCYBOX_SELECTOR .close-pop_fit"
            )
        ).lastOrNull { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: Exception) {
                false
            }
        }
    }!!

    clickFreshElement(driver, closeBtn)

    Thread.sleep(500)
}

private fun closeAllPopups(driver: WebDriver) {
    repeat(6) {
        val closeButtons = driver.findElements(
            By.cssSelector(
                "$VISIBLE_FANCYBOX_SELECTOR [data-fancybox-close], " +
                        "$VISIBLE_FANCYBOX_SELECTOR .f-button.is-close, " +
                        "$VISIBLE_FANCYBOX_SELECTOR .carousel__button.is-close, " +
                        "$VISIBLE_FANCYBOX_SELECTOR .close-pop_fit"
            )
        ).filter { el ->
            try {
                el.isDisplayed && el.isEnabled
            } catch (_: Exception) {
                false
            }
        }

        if (closeButtons.isEmpty()) {
            return
        }

        try {
            clickFreshElement(driver, closeButtons.last())
        } catch (e: Exception) {
            println("Не удалось закрыть popup кнопкой: ${e.message}")

            try {
                driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE)
            } catch (_: Exception) {
            }

            return
        }

        Thread.sleep(500)
    }

    try {
        val wait = WebDriverWait(driver, Duration.ofSeconds(10))

        wait.until { d ->
            d.findElements(By.cssSelector(VISIBLE_FANCYBOX_SELECTOR))
                .none { el ->
                    try {
                        el.isDisplayed
                    } catch (_: Exception) {
                        false
                    }
                }
        }
    } catch (_: Exception) {
        println("Не дождался полного закрытия popup, продолжаю")
    }
}

private fun waitScheduleVisible(driver: WebDriver) {
    val wait = WebDriverWait(driver, Duration.ofSeconds(20))

    wait.until { d ->
        d.findElements(
            By.cssSelector(
                "[data-body-schedule], [data-slider-date], [data-timetable-item], " +
                        ".schedule_fit, .date-schedule_fit, .box-schedule_fit, .item-konju_fit"
            )
        ).any { el ->
            try {
                el.isDisplayed
            } catch (_: Exception) {
                false
            }
        }
    }

    println("Расписание снова доступно")
}

// =========================
// ОСНОВНОЙ ВЫБОР ТРЕНИРОВОК
// =========================

private fun selectTraining(
    driver: WebDriver,
    bookingMonday: LocalDate,
    targetTrainings: List<Training>,
    allowWaitList: Boolean
): List<String> {
    val results = mutableListOf<String>()

    val trainingsByDay = targetTrainings.groupBy { it.day }

    fun openDay(dayName: String, refresh: Boolean = false) {
        if (refresh) {
            driver.navigate().refresh()
            waitCabinetLoaded(driver)
            openSchedulePage(driver)
        }

        closeAllPopups(driver)
        clickDayMode(driver)
        openDateInSchedule(driver, bookingMonday, dayName)
        waitScheduleItemsLoaded(driver)
    }

    for ((dayName, trainings) in trainingsByDay) {
        println("Открываю день: $dayName")
        openDay(dayName)

        for (training in trainings) {
            println("Пытаюсь записаться: ${training.day} ${training.time} ${training.name}")

            var result = ReservationResult.NOT_FOUND

            attemptLoop@ for (attempt in 0 until 5) {
                try {
                    if (attempt > 0) {
                        println("Повторная попытка ${attempt + 1} для ${training.name}")
                        Thread.sleep((attempt * 1000L).coerceAtMost(4000L))
                        openDay(dayName, refresh = true)
                    }

                    if (!openTrainingCardOnly(driver, training)) {
                        result = ReservationResult.NOT_FOUND
                        continue@attemptLoop
                    }

                    result = reserveOpenedTraining(
                        driver = driver,
                        training = training,
                        allowWaitList = allowWaitList
                    )

                    if (result != ReservationResult.UNKNOWN) {
                        break@attemptLoop
                    }
                } catch (e: Exception) {
                    result = ReservationResult.ERROR
                    println("Ошибка попытки ${attempt + 1}: ${e.message}")
                } finally {
                    try {
                        closeAllPopups(driver)
                    } catch (_: Exception) {
                    }
                }
            }

            val message = "$result: ${training.day} ${training.time} ${training.name}"
            println("Результат записи: $message")
            results.add(message)

            waitScheduleVisible(driver)
            openDay(dayName)
        }
    }

    println("=== ИТОГИ ЗАПИСИ ===")
    results.forEach { println(it) }
    return results
}

// =========================
// ДИАГНОСТИКА
// =========================

private fun debugSchedulePage(driver: WebDriver) {
    val root = driver.findElement(By.cssSelector("#fit-cabinet, div[data-fit-cabinet]"))

    println("=== ТЕКСТ РАСПИСАНИЯ ===")
    println(root.text.take(3000))

    println("=== ЭЛЕМЕНТЫ ДАТ ===")

    root.findElements(By.cssSelector(".date-schedule_fit")).forEachIndexed { index, el ->
        try {
            if (el.text.trim().isNotBlank()) {
                println("$index | text='${el.text.replace("\n", " ")}' | class='${el.getAttribute("class")}'")
            }
        } catch (_: Exception) {
        }
    }

    println("=== ВОЗМОЖНЫЕ ЗАНЯТИЯ ===")

    root.findElements(By.cssSelector(".item-konju_fit, .box-schedule_fit")).forEachIndexed { index, el ->
        try {
            val text = el.text.trim()
            if (text.isNotBlank()) {
                println("[$index]")
                println(text)
                println("-----")
            }
        } catch (_: Exception) {
        }
    }
}

// =========================
// MAIN
// =========================

fun main(args: Array<String>) {
    val configFile = configFileFromArgs(args)
    val botConfig = loadBotConfig(configFile)
    val zone = ZoneId.of(botConfig.timezone)
    val bookingWindow = resolveBookingWindow(zone)

    val bookingMonday = if (USE_CURRENT_WEEK_FOR_TEST) {
        LocalDate.now(zone)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    } else {
        bookingWindow.bookingMonday
    }

    println("Дата открытия записи: ${bookingWindow.openMonday}")
    println("Целевое время открытия записи: ${bookingWindow.targetTime}")
    println("Неделя записи начинается: $bookingMonday")
    println("USE_CURRENT_WEEK_FOR_TEST=$USE_CURRENT_WEEK_FOR_TEST")
    println("Файл настроек: ${configFile.absolutePath}")

    var driver: WebDriver? = null

    try {
        val activeDriver = createDriver()
        driver = activeDriver
        login(activeDriver)
        waitCabinetLoaded(activeDriver)
        openSchedulePage(activeDriver)

        val trainingCatalog = discoverTrainingNames(activeDriver)
        val launchConfig = showLaunchWindow(
            bookingMonday = bookingMonday,
            bookingWindow = bookingWindow,
            initialTrainings = botConfig.trainings,
            initialTrainingCatalog = trainingCatalog,
            defaultAllowWaitList = botConfig.allowWaitList,
            refreshTrainingCatalog = { discoverTrainingNames(activeDriver) }
        )

        if (launchConfig == null) {
            println("Запуск отменён пользователем.")
            return
        }

        saveBotConfig(
            configFile,
            botConfig.copy(
                allowWaitList = launchConfig.allowWaitList,
                trainings = launchConfig.trainings
            )
        )
        println("Расписание сохранено в ${configFile.absolutePath}")

        println("Будет выполнена запись на тренировки:")
        launchConfig.trainings.forEach {
            println("${it.day} ${it.time} — ${it.name}")
        }

        if (launchConfig.waitUntilMidnight) {
            if (USE_CURRENT_WEEK_FOR_TEST) {
                println("Выбрано ожидание 00:00, но включён USE_CURRENT_WEEK_FOR_TEST=true. В тестовом режиме ожидание отключено.")
            } else if (bookingWindow.shouldWait) {
                println("Выбрано ожидание 00:00. Жду: ${bookingWindow.targetTime}")

                sleepUntil(bookingWindow.targetTime)

                println("Наступило время записи. Обновляю страницу.")

                activeDriver.navigate().refresh()
                waitCabinetLoaded(activeDriver)
                openSchedulePage(activeDriver)
            } else {
                println("Выбрано ожидание 00:00, но целевое время уже наступило. Начинаю запись сразу.")
            }
        } else {
            println("Выбран запуск сразу. Начинаю запись без ожидания.")
        }

        selectTraining(
            driver = activeDriver,
            bookingMonday = bookingMonday,
            targetTrainings = launchConfig.trainings,
            allowWaitList = launchConfig.allowWaitList
        )

        println("Работа скрипта завершена успешно.")
    } catch (e: Exception) {
        println("Ошибка выполнения скрипта:")
        e.printStackTrace()

        try {
            driver?.let { dumpPage(it, "error-page") }
        } catch (_: Exception) {
        }
    } finally {
        println("Скрипт завершён")

        if (CLOSE_BROWSER_AFTER_FINISH) {
            driver?.quit()
        }
    }
}
