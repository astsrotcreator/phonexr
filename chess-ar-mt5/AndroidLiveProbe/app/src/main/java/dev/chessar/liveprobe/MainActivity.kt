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

/** Only tests live video support. Does not upload, record, or analyze images. */
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
            text = "Chess AR — MT5+ Live Probe v0.5"
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
        column.addView(button("ЭКСПЕРИМЕНТ: запрос RTSP без флага Live") { probeDirectRtsp() })
        jpegButton = button("3. JPEG Live — открыть камеру") { startLive() }
        jpegButton.isEnabled = false
        column.addView(jpegButton)
        rtspButton = button("4. RTSP — получить адрес") { requestRtsp() }
        rtspButton.isEnabled = false
        column.addView(rtspButton)
        openButton = button("5. RTSP — открыть в VLC") { openRtsp() }
        openButton.isEnabled = false
        column.addView(openButton)
        column.addView(button("Остановить поток") { stopStream(); status.text = "Остановлено" })
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xffeff4ef.toInt())
        }
        column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650))
        column.addView(TextView(this).apply {
            text = "Это тест SDK AB_MATE. Если очки не найдены или поток недоступен, это ещё не значит, что камера технически не может транслировать. Перед подключением останови AIBuds. RTSP может потребовать VLC."
            setPadding(0,12,0,8)
        })
        setContentView(ScrollView(this).apply { addView(column) })
        connectionJob = sdk.connector.observeConnectorState()
            .startWithItem(sdk.connector.getConnectorState())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                connected = state == WKConnectorState.CONNECTED
                status.text = "Подключение: $state"
                if (connected) check()
                else {
                    jpegButton.isEnabled = false
                    rtspButton.isEnabled = false
                    stopStream()
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
        stopStream()
        scanJob?.dispose()
        connectionJob?.dispose()
        sdk.connector.close()
        super.onDestroy()
    }
}
