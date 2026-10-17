package ir.tarazino.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.Calendar
import kotlin.math.abs

enum class TxType(val titleFa: String) {
    INCOME("درآمد"),
    EXPENSE("هزینه"),
    TRANSFER("انتقال بین حساب‌ها"),
    RECEIVABLE("دریافت طلب"),
    DEBT("پرداخت بدهی")
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val typeName: String = "حساب بانکی",
    val balanceRials: Long,
    val isBusiness: Boolean = false,
    val isDemo: Boolean = false
)

@Entity(tableName = "transactions")
data class TxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountRials: Long,
    val type: TxType,
    val category: String,
    val description: String,
    val personName: String = "",
    val accountId: Long,
    val targetAccountId: Long? = null,
    val isBusiness: Boolean = false,
    val isDemo: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "documents")
data class DocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val uriString: String,
    val personName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AppDao {
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccount(id: Long): AccountEntity?

    @Insert
    suspend fun insertAccount(acc: AccountEntity): Long

    @Update
    suspend fun updateAccount(acc: AccountEntity)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeTransactions(): Flow<List<TxEntity>>

    @Insert
    suspend fun insertTx(tx: TxEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTxById(id: Long)

    @Query("SELECT * FROM documents ORDER BY timestamp DESC")
    fun observeDocs(): Flow<List<DocEntity>>

    @Insert
    suspend fun insertDoc(doc: DocEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocById(id: Long)

    @Query("DELETE FROM transactions WHERE isDemo = 1")
    suspend fun clearDemoTxs()

    @Query("DELETE FROM accounts WHERE isDemo = 1")
    suspend fun clearDemoAccs()

    @Transaction
    suspend fun recordTransaction(tx: TxEntity) {
        val src = getAccount(tx.accountId) ?: return
        when (tx.type) {
            TxType.INCOME, TxType.RECEIVABLE -> {
                updateAccount(src.copy(balanceRials = src.balanceRials + tx.amountRials))
            }
            TxType.EXPENSE, TxType.DEBT -> {
                updateAccount(src.copy(balanceRials = src.balanceRials - tx.amountRials))
            }
            TxType.TRANSFER -> {
                val dst = tx.targetAccountId?.let { getAccount(it) } ?: return
                if (dst.id != src.id) {
                    updateAccount(src.copy(balanceRials = src.balanceRials - tx.amountRials))
                    updateAccount(dst.copy(balanceRials = dst.balanceRials + tx.amountRials))
                }
            }
        }
        insertTx(tx)
    }
}

@Database(entities = [AccountEntity::class, TxEntity::class, DocEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
}

fun formatMoney(rials: Long, useToman: Boolean = true, persianDigits: Boolean = true, hide: Boolean = false): String {
    val unit = if (useToman) "تومان" else "ریال"
    if (hide) return "****** $unit"
    val value = if (useToman) abs(rials) / 10L else abs(rials)
    val raw = DecimalFormat("#,###").format(value)
    val localized = if (persianDigits) {
        raw.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")
    } else raw
    return (if (rials < 0 && value > 0) "−" else "") + localized + " " + unit
}

fun toJalaliReadable(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val gy = cal.get(Calendar.YEAR)
    val gm = cal.get(Calendar.MONTH) + 1
    val gd = cal.get(Calendar.DAY_OF_MONTH)
    val gDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    val gy2 = if (gm > 2) gy + 1 else gy
    var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDays[gm - 1]
    var jy = -1595 + (33 * (days / 12053))
    days %= 12053
    jy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        jy += (days - 1) / 365
        days = (days - 1) % 365
    }
    val jm = if (days < 186) 1 + (days / 31) else 7 + ((days - 186) / 30)
    val jd = if (days < 186) 1 + (days % 31) else 1 + ((days - 186) % 30)
    val months = listOf("", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    val raw = "$jd ${months.getOrElse(jm) { "" }} $jy"
    return raw.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = Room.databaseBuilder(applicationContext, AppDb::class.java, "tarazino_v2.db")
            .fallbackToDestructiveMigration()
            .build()
        setContent {
            TarazinoMainApp(db.dao())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TarazinoMainApp(dao: AppDao) {
    val scope = rememberCoroutineScope()
    val accounts by dao.observeAccounts().collectAsState(initial = emptyList())
    val transactions by dao.observeTransactions().collectAsState(initial = emptyList())
    val docs by dao.observeDocs().collectAsState(initial = emptyList())

    var themeMode by remember { mutableIntStateOf(0) }
    var useToman by remember { mutableStateOf(true) }
    var persianDigits by remember { mutableStateOf(true) }
    var hideAmounts by remember { mutableStateOf(false) }

    var currentTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    var showAddTxDialog by remember { mutableStateOf(false) }
    var showAddAccDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var selectedTxForDetails by remember { mutableStateOf<TxEntity?>(null) }

    val colorScheme = when (themeMode) {
        0 -> darkColorScheme(
            primary = Color(0xFF10B981),
            secondary = Color(0xFFF59E0B),
            background = Color(0xFF0B1120),
            surface = Color(0xFF1E293B)
        )
        1 -> lightColorScheme(
            primary = Color(0xFF0D9488),
            secondary = Color(0xFFD97706),
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF)
        )
        else -> darkColorScheme(
            primary = Color(0xFF8B5CF6),
            secondary = Color(0xFFEC4899),
            background = Color(0xFF0F1026),
            surface = Color(0xFF1E1B4B)
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colorScheme) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("ترازینو (Tarazino)", fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "امروز: " + toJalaliReadable(System.currentTimeMillis()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { hideAmounts = !hideAmounts }) {
                                Icon(if (hideAmounts) Icons.Default.VisibilityOff else Icons.Default.Visibility, "مخفی کردن مبالغ")
                            }
                            IconButton(onClick = { showAddAccDialog = true }) {
                                Icon(Icons.Default.AccountBalanceWallet, "حساب جدید")
                            }
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(Icons.Default.Settings, "تنظیمات و درباره ما")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (accounts.isEmpty()) showAddAccDialog = true else showAddTxDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("ثبت سریع", fontWeight = FontWeight.Bold)
                    }
                },
                bottomBar = {
                    NavigationBar {
                        val tabs = listOf(
                            "خانه" to Icons.Default.Home,
                            "تراکنش‌ها" to Icons.Default.ReceiptLong,
                            "اسناد" to Icons.Default.AttachFile,
                            "کسب‌وکار" to Icons.Default.BusinessCenter,
                            "گزارش‌ها" to Icons.Default.Assessment
                        )
                        tabs.forEachIndexed { idx, pair ->
                            NavigationBarItem(
                                selected = currentTab == idx,
                                onClick = { currentTab = idx },
                                icon = { Icon(pair.second, null) },
                                label = { Text(pair.first, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            ) { pad ->
                val isBizMode = currentTab == 3
                val activeAccs = accounts.filter { if (isBizMode) it.isBusiness else !it.isBusiness }
                val activeTxs = transactions.filter { if (isBizMode) it.isBusiness else !it.isBusiness }
                val totalBalance = activeAccs.sumOf { it.balanceRials }
                val totalIncome = activeTxs.filter { it.type == TxType.INCOME }.sumOf { it.amountRials }
                val totalExpense = activeTxs.filter { it.type == TxType.EXPENSE }.sumOf { it.amountRials }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (currentTab == 2) {
                        item {
                            var docTitle by remember { mutableStateOf("") }
                            var docDesc by remember { mutableStateOf("") }
                            var pickedUri by remember { mutableStateOf("") }
                            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                                if (uri != null) {
                                    pickedUri = uri.toString()
                                    if (docTitle.isBlank()) docTitle = "سند مالی پیوست‌شده"
                                }
                            }
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("بایگانی اسناد، رسیدها و فاکتورها", fontWeight = FontWeight.Bold)
                                    Text("فایل‌های PDF یا تصویر رسید را با انتخاب صریح از حافظه گوشی پیوست کنید.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.AttachFile, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (pickedUri.isEmpty()) "انتخاب فایل از گوشی" else "فایل انتخاب شد (تغییر)")
                                    }
                                    OutlinedTextField(value = docTitle, onValueChange = { docTitle = it }, label = { Text("عنوان سند") }, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = docDesc, onValueChange = { docDesc = it }, label = { Text("توضیحات کامل سند") }, modifier = Modifier.fillMaxWidth())
                                    Button(
                                        onClick = {
                                            if (docTitle.isNotBlank()) {
                                                scope.launch {
                                                    dao.insertDoc(DocEntity(title = docTitle, description = docDesc, uriString = pickedUri))
                                                    docTitle = ""; docDesc = ""; pickedUri = ""
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("ذخیره در بایگانی اسناد") }
                                }
                            }
                        }
                        items(docs) { d ->
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.title, fontWeight = FontWeight.Bold)
                                        if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodySmall)
                                        Text(toJalaliReadable(d.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { scope.launch { dao.deleteDocById(d.id) } }) {
                                        Icon(Icons.Default.DeleteOutline, null)
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (isBizMode) "پرونده مالی کسب‌وکار (مجزا)" else "موجودی کل حساب‌های شخصی")
                                        Text(toJalaliReadable(System.currentTimeMillis()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(
                                        formatMoney(totalBalance, useToman, persianDigits, hideAmounts),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("درآمد دوره", style = MaterialTheme.typography.labelSmall)
                                            Text(formatMoney(totalIncome, useToman, persianDigits, hideAmounts), color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                        }
                                        Column {
                                            Text("هزینه دوره", style = MaterialTheme.typography.labelSmall)
                                            Text(formatMoney(totalExpense, useToman, persianDigits, hideAmounts), color = Color(0xFFF43F5E), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        if (currentTab == 0 || currentTab == 4) {
                            item {
                                val maxVal = maxOf(totalIncome, totalExpense, 1L).toFloat()
                                val incRatio = (totalIncome.toFloat() / maxVal).coerceIn(0.05f, 1f)
                                val expRatio = (totalExpense.toFloat() / maxVal).coerceIn(0.05f, 1f)
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("نمودار تحلیلی درآمد و هزینه", fontWeight = FontWeight.Bold)
                                        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                                            val barH = 18.dp.toPx()
                                            drawRoundRect(color = Color(0xFF10B981).copy(alpha = 0.2f), size = Size(size.width, barH), cornerRadius = CornerRadius(12f, 12f))
                                            drawRoundRect(color = Color(0xFF10B981), size = Size(size.width * incRatio, barH), cornerRadius = CornerRadius(12f, 12f))
                                            val y2 = barH + 12.dp.toPx()
                                            drawRoundRect(color = Color(0xFFF43F5E).copy(alpha = 0.2f), topLeft = Offset(0f, y2), size = Size(size.width, barH), cornerRadius = CornerRadius(12f, 12f))
                                            drawRoundRect(color = Color(0xFFF43F5E), topLeft = Offset(0f, y2), size = Size(size.width * expRatio, barH), cornerRadius = CornerRadius(12f, 12f))
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("حساب‌ها و کیف پول‌ها", fontWeight = FontWeight.Bold)
                                TextButton(onClick = { showAddAccDialog = true }) { Text("+ ایجاد حساب جدید") }
                            }
                        }

                        if (accounts.isEmpty()) {
                            item {
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("به ترازینو خوش آمدید! هنوز حسابی ثبت نشده است.", fontWeight = FontWeight.Bold)
                                        Text("می‌توانید اولین حساب واقعی خود را بسازید یا برای تست برنامه، داده‌های آزمایشی را بارگذاری کنید.", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(onClick = { showAddAccDialog = true }) { Text("ساخت اولین حساب") }
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    val bId = dao.insertAccount(AccountEntity(name = "بانک ملت (آزمایشی)", balanceRials = 450_000_000L, isDemo = true))
                                                    val wId = dao.insertAccount(AccountEntity(name = "کیف پول (آزمایشی)", balanceRials = 25_000_000L, isDemo = true))
                                                    val bizId = dao.insertAccount(AccountEntity(name = "حساب فروشگاه (آزمایشی)", balanceRials = 950_000_000L, isBusiness = true, isDemo = true))
                                                    dao.recordTransaction(TxEntity(amountRials = 320_000_000L, type = TxType.INCOME, category = "حقوق و دستمزد", description = "واریز حقوق ماهانه شرکت پارس", personName = "شرکت پارس", accountId = bId, isDemo = true))
                                                    dao.recordTransaction(TxEntity(amountRials = 48_500_000L, type = TxType.EXPENSE, category = "خوراک و سوپرمارکت", description = "خرید هفتگی فروشگاه زنجیره‌ای", personName = "هایپراستار", accountId = bId, isDemo = true))
                                                    dao.recordTransaction(TxEntity(amountRials = 30_000_000L, type = TxType.TRANSFER, category = "انتقال داخلی", description = "شارژ کیف پول روزمره", accountId = bId, targetAccountId = wId, isDemo = true))
                                                    dao.recordTransaction(TxEntity(amountRials = 180_000_000L, type = TxType.INCOME, category = "فروش کالا", description = "فاکتور شماره ۱۰۴ - فروش تجهیزات", personName = "مهندس رضایی", accountId = bizId, isBusiness = true, isDemo = true))
                                                }
                                            }) { Text("داده آزمایشی") }
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    accounts.forEach { acc ->
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, if (acc.isBusiness) MaterialTheme.colorScheme.secondary else Color.Gray.copy(alpha = 0.25f))
                                        ) {
                                            Column(Modifier.padding(14.dp).width(185.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(acc.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(if (acc.isBusiness) "حساب کسب‌وکار" else acc.typeName, style = MaterialTheme.typography.labelSmall)
                                                Text(formatMoney(acc.balanceRials, useToman, persianDigits, hideAmounts), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (currentTab == 1) {
                            item {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("جست‌وجو در توضیحات، دسته‌بندی یا نام شخص...") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }

                        item {
                            Text("لیست تراکنش‌ها (برای دیدن توضیحات کامل روی هر مورد بزنید)", fontWeight = FontWeight.Bold)
                        }

                        val displayedTxs = activeTxs.filter {
                            searchQuery.isBlank() ||
                                it.description.contains(searchQuery, ignoreCase = true) ||
                                it.category.contains(searchQuery, ignoreCase = true) ||
                                it.personName.contains(searchQuery, ignoreCase = true)
                        }

                        items(displayedTxs) { itemTx ->
                            val accName = accounts.find { it.id == itemTx.accountId }?.name ?: "حساب"
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTxForDetails = itemTx }
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(itemTx.category + " • " + itemTx.type.titleFa, fontWeight = FontWeight.Bold)
                                        Text(
                                            formatMoney(itemTx.amountRials, useToman, persianDigits, hideAmounts),
                                            color = when (itemTx.type) {
                                                TxType.INCOME, TxType.RECEIVABLE -> Color(0xFF10B981)
                                                TxType.EXPENSE, TxType.DEBT -> Color(0xFFF43F5E)
                                                TxType.TRANSFER -> Color(0xFF38BDF8)
                                            },
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    if (itemTx.description.isNotBlank()) {
                                        Text("توضیحات: " + itemTx.description, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = accName + (if (itemTx.personName.isNotBlank()) " | طرف حساب: " + itemTx.personName else ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = toJalaliReadable(itemTx.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(75.dp)) }
                    }
                }
            }

            selectedTxForDetails?.let { itemTx ->
                AlertDialog(
                    onDismissRequest = { selectedTxForDetails = null },
                    title = { Text("جزئیات کامل تراکنش") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("نوع: " + itemTx.type.titleFa, fontWeight = FontWeight.Bold)
                            Text("دسته‌بندی: " + itemTx.category)
                            Text("مبلغ: " + formatMoney(itemTx.amountRials, useToman, persianDigits, false))
                            Text("تاریخ شمسی: " + toJalaliReadable(itemTx.timestamp))
                            Text("طرف حساب / شخص: " + (itemTx.personName.ifBlank { "ثبت نشده" }))
                            Text("توضیحات کامل:
" + (itemTx.description.ifBlank { "بدون توضیحات" }))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedTxForDetails = null }) { Text("بستن") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                scope.launch { dao.deleteTxById(itemTx.id) }
                                selectedTxForDetails = null
                            }
                        ) { Text("حذف تراکنش", color = Color(0xFFF43F5E)) }
                    }
                )
            }

            if (showSettingsSheet) {
                ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("تنظیمات، تم‌ها و درباره ما", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)

                        Text("انتخاب تم برنامه (۳ تم استاندارد):", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = themeMode == 0, onClick = { themeMode = 0 }, label = { Text("تیره لوکس") })
                            FilterChip(selected = themeMode == 1, onClick = { themeMode = 1 }, label = { Text("روشن مینیمال") })
                            FilterChip(selected = themeMode == 2, onClick = { themeMode = 2 }, label = { Text("اختصاصی بنفش") })
                        }

                        Text("واحد پول نمایشی:", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = useToman, onClick = { useToman = true }, label = { Text("تومان") })
                            FilterChip(selected = !useToman, onClick = { useToman = false }, label = { Text("ریال") })
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("نمایش اعداد به صورت فارسی (۰۱۲۳...)")
                            Switch(checked = persianDigits, onCheckedChange = { persianDigits = it })
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    dao.clearDemoTxs()
                                    dao.clearDemoAccs()
                                }
                                showSettingsSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حذف کامل داده‌های آزمایشی (Demo)")
                        }

                        HorizontalDivider()

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("درباره ما و اپلیکیشن ترازینو (Tarazino)", fontWeight = FontWeight.ExtraBold)
                                }
                                Text(
                                    "ترازینو یک سامانه جامع، امن و ۱۰۰٪ آفلاین برای مدیریت مالی شخصی و حسابداری کسب‌وکارهای کوچک است.
" +
                                    "• حفظ کامل حریم خصوصی: بدون نیاز به دسترسی پیامک (SMS) و بدون ارسال اطلاعات به هیچ سروری.
" +
                                    "• دقت ۱۰۰٪ محاسباتی: ذخیره‌سازی مبالغ با نوع صحیح (Long) بدون خطای اعشاری.
" +
                                    "• تفکیک اصولی انتقال بین حساب‌ها، دریافت طلب و پرداخت بدهی از سود و زیان.
" +
                                    "• نسخه فعلی: 1.1.0 (نسخه کامل فاز اول و هسته مالی فاز دوم)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            if (showAddAccDialog) {
                var name by remember { mutableStateOf("") }
                var typeName by remember { mutableStateOf("حساب بانکی") }
                var initBal by remember { mutableStateOf("") }
                var isBiz by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { showAddAccDialog = false },
                    title = { Text("ایجاد حساب یا کیف پول جدید") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام حساب (مثلاً بانک سامان)") })
                            OutlinedTextField(value = typeName, onValueChange = { typeName = it }, label = { Text("نوع (بانکی، نقد، کیف پول)") })
                            OutlinedTextField(
                                value = initBal,
                                onValueChange = { initBal = it },
                                label = { Text("موجودی اولیه (" + (if (useToman) "تومان" else "ریال") + ")") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isBiz, onCheckedChange = { isBiz = it })
                                Text("این حساب مربوط به کسب‌وکار است")
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (name.isNotBlank()) {
                                val factor = if (useToman) 10L else 1L
                                val rials = (initBal.toLongOrNull() ?: 0L) * factor
                                scope.launch {
                                    dao.insertAccount(AccountEntity(name = name.trim(), typeName = typeName.trim(), balanceRials = rials, isBusiness = isBiz))
                                }
                                showAddAccDialog = false
                            }
                        }) { Text("ذخیره حساب") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddAccDialog = false }) { Text("انصراف") }
                    }
                )
            }

            if (showAddTxDialog && accounts.isNotEmpty()) {
                var amountStr by remember { mutableStateOf("") }
                var desc by remember { mutableStateOf("") }
                var person by remember { mutableStateOf("") }
                var cat by remember { mutableStateOf("عمومی") }
                var txType by remember { mutableStateOf(TxType.EXPENSE) }
                var selectedAccId by remember { mutableLongStateOf(accounts.first().id) }
                var targetAccId by remember { mutableLongStateOf(accounts.last().id) }

                LaunchedEffect(desc) {
                    if (desc.contains("سوپرمارکت") || desc.contains("نان") || desc.contains("غذا")) cat = "خوراک و سوپرمارکت"
                    else if (desc.contains("اسنپ") || desc.contains("تاکسی") || desc.contains("بنزین")) cat = "حمل‌ونقل"
                    else if (desc.contains("حقوق") || desc.contains("پاداش")) cat = "حقوق و دستمزد"
                    else if (desc.contains("اجاره") || desc.contains("قبض")) cat = "مسکن و قبوض"
                }

                AlertDialog(
                    onDismissRequest = { showAddTxDialog = false },
                    title = { Text("ثبت رویداد مالی جدید") },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TxType.entries.forEach { t ->
                                    FilterChip(selected = txType == t, onClick = { txType = t }, label = { Text(t.titleFa) })
                                }
                            }
                            OutlinedTextField(
                                value = amountStr,
                                onValueChange = { amountStr = it },
                                label = { Text("مبلغ (" + (if (useToman) "تومان" else "ریال") + ")") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = desc,
                                onValueChange = { desc = it },
                                label = { Text("توضیحات تراکنش (مثلاً خرید سوپرمارکت)") }
                            )
                            OutlinedTextField(
                                value = cat,
                                onValueChange = { cat = it },
                                label = { Text("دسته‌بندی") }
                            )
                            OutlinedTextField(
                                value = person,
                                onValueChange = { person = it },
                                label = { Text("شخص یا طرف حساب مرتبط (اختیاری)") }
                            )
                            Text("انتخاب حساب مبدأ:", style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                accounts.forEach { a ->
                                    FilterChip(selected = selectedAccId == a.id, onClick = { selectedAccId = a.id }, label = { Text(a.name) })
                                }
                            }
                            if (txType == TxType.TRANSFER && accounts.size > 1) {
                                Text("انتخاب حساب مقصد:", style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    accounts.filter { it.id != selectedAccId }.forEach { a ->
                                        FilterChip(selected = targetAccId == a.id, onClick = { targetAccId = a.id }, label = { Text(a.name) })
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val factor = if (useToman) 10L else 1L
                            val rials = (amountStr.toLongOrNull() ?: 0L) * factor
                            if (rials > 0) {
                                val srcAcc = accounts.find { it.id == selectedAccId }
                                scope.launch {
                                    dao.recordTransaction(
                                        TxEntity(
                                            amountRials = rials,
                                            type = txType,
                                            category = cat.ifBlank { "عمومی" },
                                            description = desc.trim(),
                                            personName = person.trim(),
                                            accountId = selectedAccId,
                                            targetAccountId = if (txType == TxType.TRANSFER) targetAccountId else null,
                                            isBusiness = srcAcc?.isBusiness ?: false
                                        )
                                    )
                                }
                                showAddTxDialog = false
                            }
                        }) { Text("ثبت نهایی") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddTxDialog = false }) { Text("انصراف") }
                    }
                )
            }
        }
    }
}
