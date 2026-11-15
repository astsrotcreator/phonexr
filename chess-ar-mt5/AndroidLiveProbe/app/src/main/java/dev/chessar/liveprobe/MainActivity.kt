package dev.chessar.liveprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import com.topstep.wearkit.abmate.apis.AbMateSDK
import com.bluetrum.devicemanager.models.AIPicPackage
import androidx.lifecycle.Observer
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.topstep.wearkit.apis.model.core.WKAuthMode
import com.topstep.wearkit.apis.model.core.WKConnectorState
import com.topstep.wearkit.apis.model.core.WKDeviceType
import com.topstep.wearkit.apis.model.camera.WKLiveSession
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** Tests on-device camera capabilities. AI photo uses Bluetooth, no cloud or uploads. */
class MainActivity : Activity() {
    private val sdk by lazy { (application as ChessApp).wearKit }
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var image: ImageView
    private lateinit var jpegButton: Button
    private lateinit var rtspButton: Button
    private lateinit var openButton: Button
    private var connected = false
    private var jpegSupported = false
    private var scanJob: Disposable? = null
    private var connectionJob: Disposable? = null
    private var videoJob: Disposable? = null
    private var rtspJob: Disposable? = null
    private var rawProbeJob: Disposable? = null
    private var rawAddressJob: Disposable? = null
    private val aiHandler = Handler(Looper.getMainLooper())
    private var aiTimeout: Runnable? = null
    private var aiNext: Runnable? = null
    private var aiObserver: Observer<AIPicPackage?>? = null
    private var aiBuffer = ByteArrayOutputStream()
    private var aiBusy = false
    private var aiBatchRemaining = 0
    private var aiReceived = 0
    private var aiCaptureStart = 0L
    private var aiElapsedTotal = 0L
    private var aiBatchStart = 0L
    private var aiPendingShutdown = false
    private var aiButton: Button? = null
    private var batchButton: Button? = null
    private var session: WKLiveSession? = null
    private var currentImage: Bitmap? = null
    private var url: String? = null
    private var frames = 0
    private var startMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20,20,20,20)
        }
        column.addView(TextView(this).apply {
            text = "Chess AR — MT5+ AI Camera v0.7"
            textSize = 21f
            setTextColor(0xff165d35.toInt())
        })
        status = TextView(this).apply {
            text = "Разреши Bluetooth, затем запусти поиск очков."
            textSize = 15f
            setPadding(0,14,0,14)
        }
        column.addView(status)
        column.addView(button("1. Найти очки") { scan() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        column.addView(button("2. Проверить видеопоток") { check() })
        column.addView(button("Диагностика чипа и флага Live (из AIBuds)") { diagnoseHardware() })
        aiButton = button("3. AI-фото через Bluetooth (без альбома)") { captureAiPhoto() }.also {
            it.isEnabled = false
            column.addView(it)
        }
        batchButton = button("4. БЫСТРАЯ СЕРИЯ: 10 кадров без паузы 4 сек") { startAiBatch() }.also {
            it.isEnabled = false
            column.addView(it)
        }
        column.addView(button("Остановить AI-тест") {
            stopAiPhoto()
            status.text = "AI-тест остановлен"
        })
        jpegButton = button("3. JPEG Live — открыть камеру") { startLive() }
        jpegButton.isEnabled = false
        column.addView(jpegButton)
        rtspButton = button("4. RTSP — получить адрес") { requestRtsp() }
        rtspButton.isEnabled = false
        column.addView(rtspButton)
        openButton = button("5. RTSP — открыть в VLC") { openRtsp() }
        openButton.isEnabled = false
        column.addView(openButton)
        column.addView(button("Остановить обычный видеопоток") { stopStream(); status.text = "Поток остановлен" })
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xffeff4ef.toInt())
        }
        column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650))
        column.addView(TextView(this).apply {
            text = "Режим AI-фото работает без интернета. Быстрая серия — до 10 отдельных снимков: следующий снимок запрашивается после завершения предыдущего и выключения камеры, без прежней паузы 4 секунды. Это не Live-видео. Не оставляй режим включённым надолго."
            setPadding(0,12,0,8)
        })
        setContentView(ScrollView(this).apply { addView(column) })
        connectionJob = sdk.connector.observeConnectorState()
            .startWithItem(sdk.connector.getConnectorState())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                connected = state == WKConnectorState.CONNECTED
                status.text = "Подключение: $state"
                aiButton?.isEnabled = connected
                batchButton?.isEnabled = connected
                if (connected) check()
                else {
                    jpegButton.isEnabled = false
                    rtspButton.isEnabled = false
                    stopStream()
                    stopAiPhoto()
                }
            }, { error -> status.text = "Ошибка подключения: ${error.message}" })
        askPermissions()
    }

    private fun button(text: String, click: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { click() } }

    private fun neededPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < 31) return arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val p = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return p.toTypedArray()
    }
    private fun permitted() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    private fun askPermissions() {
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    private fun scan() {
        if (!permitted()) {
            askPermissions()
            status.text = "Нужно разрешить Bluetooth и поиск устройств"
            return
        }
        scanJob?.dispose()
        list.removeAllViews()
        status.text = "Ищу устройства AB_MATE (30 секунд)…"
        scanJob = sdk.scanner.scan(
            type = WKDeviceType.AB_MATE,
            durationSeconds = 30,
            checkLocationService = false,
            acceptEmptyName = true
        ).observeOn(AndroidSchedulers.mainThread())
         .subscribe({ result ->
             val address = result.device.address
             if ((0 until list.childCount).any { list.getChildAt(it).tag == address }) return@subscribe
             list.addView(button("${(result.name?.ifBlank { "(без названия)" } ?: "(без названия)")} — $address") {
                 connect(address)
             }.apply { tag = address })
         }, { error -> status.text = "Ошибка поиска: ${error.message}" })
    }

    private fun connect(address: String) {
        scanJob?.dispose()
        status.text = "Подключаю $address"
        try {
            sdk.connector.connect(
                type = WKDeviceType.AB_MATE,
                address = address,
                authMode = WKAuthMode.AUTO,
                authCode = null,
                userId = "chess_ar_mt5_local_test"
            )
        } catch (e: Exception) {
            status.text = "Ошибка подключения: ${e.message}"
        }
    }

    private fun check() {
        if (!connected) { status.text = "Сначала подключи очки"; return }
        try {
            jpegSupported = sdk.cameraAbility.compat.isSupportLive()
            val rtsp = sdk.fileAbility.compat.isSupportRtsp()
            status.text = "Подключено. JPEG Live: $jpegSupported | RTSP: $rtsp"
            jpegButton.isEnabled = jpegSupported
            rtspButton.isEnabled = rtsp
        } catch (e: Exception) {
            status.text = "Ошибка определения возможностей: ${e.message}"
        }
    }

    /**
     * Read-only hardware diagnostics.
     * AIBuds LiveCapability checks device ability bit 0x40 and copro type
     * before allowing JPEG/RTSP. Data is read from SDK cache only.
     */
    private fun diagnoseHardware() {
        if (!connected) {
            status.text = "Сначала подключи MT5+ через поиск устройства."
            return
        }
        try {
            val ability: Any = sdk.cameraAbility
            val connector = fieldValue(ability, "connector")
                ?: error("Нет поля connector в ${ability.javaClass.name}")
            val method = connector.javaClass.methods.firstOrNull { it.name == "getCommManager" && it.parameterCount == 0 }
                ?: connector.javaClass.declaredMethods.firstOrNull { it.name == "getCommManager" && it.parameterCount == 0 }
                ?: error("Нет getCommManager в ${connector.javaClass.name}")
            method.isAccessible = true
            val manager = method.invoke(connector) ?: error("CommManager ещё не готов")
            fun call(name: String): Any? {
                val m = manager.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: manager.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: error("Нет $name в SDK")
                m.isAccessible = true
                return m.invoke(manager)
            }
            val bytes = call("getCachedDeviceAbility") as? ByteArray
            val copro = (call("getCachedDeviceCoprocessor") as? Number)?.toInt()
            val flag = bytes?.firstOrNull()?.toInt()?.and(0xff)?.and(0x40) == 0x40
            val hex = bytes?.take(12)?.joinToString(" ") { "%02X".format(it.toInt() and 0xff) } ?: "не получены"
            val notes = when(copro) {
                1,2 -> "RTSP-тип по коду AIBuds"
                3,4,5 -> "JPEG-тип по коду AIBuds"
                null -> "тип не получен"
                else -> "возможен неизвестный/неподдерживаемый тип"
            }
            status.text =
                "DEBUG MT5+ (без передачи данных)\\n" +
                "Флаг 0x40: $flag\\n" +
                "Возможности: $hex\\n" +
                "Камерный чип: $copro ($notes)\\n" +
                "JPEG Live: ${sdk.cameraAbility.compat.isSupportLive()}\\n" +
                "RTSP: ${sdk.fileAbility.compat.isSupportRtsp()}"
        } catch (e: Exception) {
            status.text = "Не удалось прочитать данные SDK: ${e.javaClass.simpleName}: ${e.message}\\nКласс: ${sdk.cameraAbility.javaClass.name}"
        }
    }

    private fun fieldValue(target: Any, fieldName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    /**
     * Sends a single documented RTSP-start command (0xE8) through the AB_MATE
     * protocol, bypassing the SDK feature advertisement check. Never changes
     * firmware, resets storage or captures personal files.
     *
     * NOTE: The device may reject the request. Even an ACK does NOT prove
     * Wi-Fi is up or that playable video is available.
     */
    private fun probeDirectRtsp() {
        if (!connected) {
            status.text = "Сначала подключи MT5+."
            return
        }
        rawProbeJob?.dispose()
        rawAddressJob?.dispose()
        try {
            val fileAbility = sdk.fileAbility as Any
            val manager = fieldValue(fileAbility, "abMate")
                ?: error("Не найден внутренний ABMateManager")
            val connector = fieldValue(fileAbility, "connector")
            val comm = if (connector != null) fieldValue(connector, "commManager") else null
            // Register address observer BEFORE sending request.
            if (comm != null) {
                val addressMethod = comm.javaClass.methods.firstOrNull {
                    it.name == "getWifiRtspAddress" && it.parameterCount == 0
                }
                val addressObservable = addressMethod?.invoke(comm)
                if (addressObservable is io.reactivex.rxjava3.core.Observable<*>) {
                    rawAddressJob = addressObservable
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ value ->
                            val received = value?.toString()?.trim()
                            if (!received.isNullOrEmpty()) {
                                url = received
                                openButton.isEnabled = true
                                status.text = "RTSP-адрес получен: $received. Для просмотра нужен доступный Wi-Fi путь и VLC."
                            }
                        }, { error -> status.text = "RTSP адрес: ${error.message}" })
                }
            }
            val method = manager.javaClass.methods.firstOrNull {
                it.name.startsWith("startRtspInternal") && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: error("В этой версии SDK нет внутренней команды запуска RTSP")
            method.isAccessible = true
            val result = method.invoke(manager, 50)
            val operation = result as? io.reactivex.rxjava3.core.Completable
                ?: error("Команда вернула неожиданный результат")
            status.text = "Отправлен ОДИН запрос RTSP (0xE8), ожидаю ответ…"
            rawProbeJob = operation
                .timeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (url.isNullOrBlank()) {
                        status.text = "Команда RTSP принята устройством, но адрес потока не получен. Это НЕ подтверждение видео."
                    }
                }, { err ->
                    val reason = err.cause ?: err
                    status.text = "Устройство/SDK отклонило прямой RTSP: ${reason.javaClass.simpleName}: ${reason.message}"
                })
        } catch (e: Exception) {
            rawAddressJob?.dispose()
            status.text = "Прямой RTSP недоступен: ${e.javaClass.simpleName}: ${e.message}"
        }
    }


    /** Official Topstep ABMateManager AI camera path (mode 1). */
    private fun managerOrNull() = (sdk.getRawSDK() as? AbMateSDK)?.abMateManager

    private fun startAiBatch() {
        if (!connected) { status.text = "Сначала подключи очки"; return }
        stopAiPhoto()
        aiReceived = 0
        aiElapsedTotal = 0L
        aiBatchRemaining = 10
        aiBatchStart = SystemClock.elapsedRealtime()
        aiPendingShutdown = false
        captureAiPhoto()
    }

    private fun captureAiPhoto() {
        if (!connected) { status.text = "Сначала подключи очки"; return }
        if (aiBusy || aiPendingShutdown) { status.text = "Камера ещё занята"; return }
        val manager = managerOrNull() ?: run {
            status.text = "ABMateManager недоступен: проверь соединение"
            return
        }
        try {
            if (aiObserver == null) {
                // The packets arrive via LiveData and may be fragmented.
                val observer = Observer<AIPicPackage?> { packet ->
                    if (packet != null && aiBusy) handleAiPacket(packet)
                }
                manager.getRepository().aiPicPackage.observeForever(observer)
                aiObserver = observer
            }
            aiBusy = true
            aiBuffer.reset()
            aiCaptureStart = SystemClock.elapsedRealtime()
            status.text = "Отправляю AI-фото в очки. Смотри на доску…"
            val onTimeout = Runnable {
                if (aiBusy) {
                    aiBusy = false
                    aiBatchRemaining = 0
                    status.text = "Нет JPEG за 15 секунд. Проверь камеру/подключение."
                    try { manager.turnOffCamera(onSuccess = {}, onFail = {}) } catch (_: Exception) {}
                }
            }
            aiTimeout = onTimeout
            aiHandler.postDelayed(onTimeout, 15000L)
            manager.turnOnCamera(1, onSuccess = {
                // An ACK is NOT proof that an image has arrived.
                if (aiBusy) status.text = "Команда принята; ожидаю JPEG-пакеты от MT5+…"
            }, onFail = {
                cancelAiTimeout()
                aiBusy = false
                aiBatchRemaining = 0
                status.text = "Очки отклонили режим AI-фото"
            })
        } catch (e: Exception) {
            cancelAiTimeout()
            aiBusy = false
            aiBatchRemaining = 0
            status.text = "Ошибка AI-фото: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun handleAiPacket(packet: AIPicPackage) {
        val bytes = packet.picFlow ?: byteArrayOf()
        val code = packet.status
        if (code == 0xFE || code == 0xFF) {
            cancelAiTimeout()
            aiBusy = false
            aiBatchRemaining = 0
            status.text = if (code == 0xFE) "Память камеры заполнена" else "Ошибка передачи изображения по Bluetooth"
            return
        }
        if (bytes.isNotEmpty()) {
            // The first JPEG segment starts a new image.
            if (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff &&
                (bytes[1].toInt() and 0xff) == 0xd8 && aiBuffer.size() > 0) {
                aiBuffer.reset()
            }
            if (aiBuffer.size() + bytes.size > 3_000_000) {
                aiBusy = false
                aiBatchRemaining = 0
                cancelAiTimeout()
                status.text = "Слишком большой кадр — остановлено"
                return
            }
            aiBuffer.write(bytes)
        }
        val data = aiBuffer.toByteArray()
        val jpegComplete = data.size >= 4 &&
            (data[0].toInt() and 0xff) == 0xff &&
            (data[1].toInt() and 0xff) == 0xd8 &&
            (data[data.size - 2].toInt() and 0xff) == 0xff &&
            (data.last().toInt() and 0xff) == 0xd9
        if (jpegComplete || code == 0) {
            cancelAiTimeout()
            aiBusy = false
            aiBuffer.reset()
            val bitmap = if (data.isNotEmpty()) BitmapFactory.decodeByteArray(data, 0, data.size) else null
            if (bitmap == null) {
                aiBatchRemaining = 0
                status.text = "Получены пакеты, но JPEG не собран. Байты: ${data.size}, статус: $code"
            } else {
                val old = currentImage
                currentImage = bitmap
                image.setImageBitmap(bitmap)
                if (old !== bitmap && old?.isRecycled == false) old.recycle()
                aiReceived++
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - aiCaptureStart
                aiElapsedTotal += elapsed
                val total = (now - aiBatchStart).coerceAtLeast(1L)
                val rate = if (aiBatchStart > 0L) String.format(java.util.Locale.US, "%.2f", aiReceived * 1000.0 / total) else "—"
                status.text = "AI-ФОТО #$aiReceived: ${bitmap.width}×${bitmap.height}, $elapsed мс, ${data.size} Б. Среднее ${aiElapsedTotal / aiReceived} мс. Серия: $rate кадр/с"
                val more = if (aiBatchRemaining > 0) {
                    aiBatchRemaining--
                    aiBatchRemaining > 0
                } else false
                aiPendingShutdown = more
                try {
                    managerOrNull()?.turnOffCamera(
                        onSuccess = {
                            aiHandler.post {
                                if (more && aiPendingShutdown && connected) {
                                    aiPendingShutdown = false
                                    val next = Runnable { aiNext = null; captureAiPhoto() }
                                    aiNext = next
                                    aiHandler.postDelayed(next, 150L)
                                } else if (!more && aiBatchStart > 0L) {
                                    status.append("\nСерия завершена: $aiReceived кадров. $rate кадр/с")
                                }
                            }
                        },
                        onFail = {
                            aiHandler.post {
                                aiBatchRemaining = 0
                                aiPendingShutdown = false
                                status.append("\nНе удалось завершить работу камеры, серия остановлена.")
                            }
                        }
                    )
                } catch (e: Exception) {
                    aiPendingShutdown = false
                    aiBatchRemaining = 0
                    status.append("\nОшибка отключения камеры: ${e.message}")
                }
                if (more) {
                    aiHandler.postDelayed({
                        if (aiPendingShutdown) {
                            aiPendingShutdown = false
                            aiBatchRemaining = 0
                            status.append("\nОстановка: камера не подтвердила закрытие за 5 секунд.")
                        }
                    }, 5000L)
                }
            }
        } else {
            status.text = "Получаю AI-фото: ${data.size} байт (статус $code)"
        }
    }

    private fun cancelAiTimeout() {
        aiTimeout?.let { aiHandler.removeCallbacks(it) }
        aiTimeout = null
    }

    private fun stopAiPhoto() {
        cancelAiTimeout()
        aiNext?.let { aiHandler.removeCallbacks(it) }
        aiNext = null
        val wasRunning = aiBusy
        aiBusy = false
        aiBatchRemaining = 0
        aiPendingShutdown = false
        aiBatchStart = 0L
        aiBuffer.reset()
        if (wasRunning) {
            try { managerOrNull()?.turnOffCamera(onSuccess = {}, onFail = {}) } catch (_: Exception) {}
        }
    }

    private fun startLive() {
        stopStream()
        if (!connected || !jpegSupported) { status.text = "Live не поддерживается SDK"; return }
        try {
            val opened = sdk.cameraAbility.openLive()
            if (opened == null) { status.text = "openLive() вернул null"; return }
            session = opened
            frames = 0
            startMs = SystemClock.elapsedRealtime()
            status.text = "Ожидание кадра с камеры…"
            videoJob = opened.video()
                .toFlowable(BackpressureStrategy.LATEST)
                .observeOn(Schedulers.computation(), false, 1)
                .map { frame -> BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) }
                .observeOn(AndroidSchedulers.mainThread(), false, 1)
                .subscribe({ bitmap ->
                    if (bitmap != null && session != null) {
                        val old = currentImage
                        currentImage = bitmap
                        image.setImageBitmap(bitmap)
                        if (old !== bitmap && old?.isRecycled == false) old.recycle()
                        frames++
                        val seconds = ((SystemClock.elapsedRealtime() - startMs) / 1000.0).coerceAtLeast(0.1)
                        status.text = "ЖИВОЕ ВИДЕО: $frames кадров, ~${"%.1f".format(frames / seconds)} FPS"
                    }
                }, { error ->
                    stopStream()
                    status.text = "Ошибка видеопотока: ${error.message}"
                })
        } catch (e: Exception) { status.text = "Live ошибка: ${e.message}" }
    }

    private fun requestRtsp() {
        stopStream()
        if (!connected) { status.text = "Нет подключения"; return }
        if (!sdk.fileAbility.compat.isSupportRtsp()) {
            status.text = "RTSP не поддерживается SDK"; return
        }
        status.text = "Получаю локальный адрес RTSP; включи Wi-Fi…"
        try {
            rtspJob = sdk.fileAbility.rtsp()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ address ->
                    url = address
                    openButton.isEnabled = true
                    status.text = "RTSP найден: $address. Нажми «Открыть в VLC»."
                }, { error -> status.text = "RTSP ошибка: ${error.message}" })
        } catch (e: Exception) { status.text = "RTSP ошибка: ${e.message}" }
    }

    private fun openRtsp() {
        val address = url ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(address), "video/*")
            })
        } catch (e: Exception) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RTSP", address))
            status.text = "Нет RTSP-плеера. Адрес скопирован. Установи VLC."
        }
    }

    private fun stopStream() {
        rawProbeJob?.dispose()
        rawProbeJob = null
        rawAddressJob?.dispose()
        rawAddressJob = null
        videoJob?.dispose()
        videoJob = null
        session?.release()
        session = null
        rtspJob?.dispose()
        rtspJob = null
        url = null
        openButton.isEnabled = false
        image.setImageDrawable(null)
        currentImage?.takeUnless { it.isRecycled }?.recycle()
        currentImage = null
    }

    override fun onDestroy() {
        stopAiPhoto()
        aiObserver?.let { observer ->
            try { managerOrNull()?.getRepository()?.aiPicPackage?.removeObserver(observer) } catch (_: Exception) {}
        }
        aiObserver = null
        stopStream()
        scanJob?.dispose()
        connectionJob?.dispose()
        sdk.connector.close()
        super.onDestroy()
    }
}
