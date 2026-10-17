package ir.tarazino.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.math.abs

// --- پایگاه داده محلی Room ---
enum class TxType(val titleFa: String) {
    INCOME("درآمد"), EXPENSE("هزینه"), TRANSFER("انتقال بین حساب‌ها")
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balanceRials: Long,
    val isBusiness: Boolean = false
)

@Entity(tableName = "transactions")
data class TxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountRials: Long,
    val type: TxType,
    val category: String,
    val description: String,
    val accountId: Long,
    val targetAccountId: Long? = null,
    val isBusiness: Boolean = false,
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

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeTransactions(): Flow<List<TxEntity>>

    @Insert
    suspend fun insertTx(tx: TxEntity)

    @Transaction
    suspend fun recordTransaction(tx: TxEntity) {
        val src = getAccount(tx.accountId) ?: return
        when (tx.type) {
            TxType.INCOME -> updateAccount(src.copy(balanceRials = src.balanceRials + tx.amountRials))
            TxType.EXPENSE -> updateAccount(src.copy(balanceRials = src.balanceRials - tx.amountRials))
            TxType.TRANSFER -> {
                val dst = tx.targetAccountId?.let { getAccount(it) } ?: return
                updateAccount(src.copy(balanceRials = src.balanceRials - tx.amountRials))
                updateAccount(dst.copy(balanceRials = dst.balanceRials + tx.amountRials))
            }
        }
        insertTx(tx)
    }
}

@Database(entities = [AccountEntity::class, TxEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
}

// --- ابزار فرمت پول ---
fun formatToman(rials: Long, hide: Boolean = false): String {
    if (hide) return "****** تومان"
    val toman = abs(rials) / 10L
    val str = DecimalFormat("#,###").format(toman)
    val persian = str.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")
    return (if (rials < 0) "−" else "") + persian + " تومان"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = Room.databaseBuilder(applicationContext, AppDb::class.java, "tarazino.db").build()
        setContent {
            TarazinoAppScreen(db.dao())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TarazinoAppScreen(dao: AppDao) {
    val scope = rememberCoroutineScope()
    val accounts by dao.observeAccounts().collectAsState(initial = emptyList())
    val transactions by dao.observeTransactions().collectAsState(initial = emptyList())

    var darkTheme by remember { mutableStateOf(true) }
    var hideAmounts by remember { mutableStateOf(false) }
    var currentTab by remember { mutableIntStateOf(0) }
    var showAddTxSheet by remember { mutableStateOf(false) }
    var showAddAccDialog by remember { mutableStateOf(false) }

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF10B981),
            secondary = Color(0xFFF59E0B),
            background = Color(0xFF0B1120),
            surface = Color(0xFF1E293B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0D9488),
            secondary = Color(0xFFD97706),
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF)
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("ترازینو (حسابداری شخصی و کسب‌وکار)", fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { hideAmounts = !hideAmounts }) {
                                Icon(if (hideAmounts) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                            IconButton(onClick = { darkTheme = !darkTheme }) {
                                Icon(Icons.Default.Palette, null)
                            }
                        }
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (accounts.isEmpty()) showAddAccDialog = true else showAddTxSheet = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("ثبت سریع", fontWeight = FontWeight.Bold)
                    }
                },
                bottomBar = {
                    NavigationBar {
                        listOf("خانه" to Icons.Default.Home, "تراکنش‌ها" to Icons.Default.ReceiptLong, "کسب‌وکار" to Icons.Default.BusinessCenter, "گزارش‌ها" to Icons.Default.Assessment).forEachIndexed { idx, pair ->
                            NavigationBarItem(
                                selected = currentTab == idx,
                                onClick = { currentTab = idx },
                                icon = { Icon(pair.second, null) },
                                label = { Text(pair.first) }
                            )
                        }
                    }
                }
            ) { pad ->
                val isBizFilter = currentTab == 2
                val filteredAccs = accounts.filter { if (isBizFilter) it.isBusiness else !it.isBusiness }
                val filteredTxs = transactions.filter { if (isBizFilter) it.isBusiness else !it.isBusiness }
                val totalBalance = filteredAccs.sumOf { it.balanceRials }
                val totalIncome = filteredTxs.filter { it.type == TxType.INCOME }.sumOf { it.amountRials }
                val totalExpense = filteredTxs.filter { it.type == TxType.EXPENSE }.sumOf { it.amountRials }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (isBizFilter) "موجودی کل حساب‌های کسب‌وکار" else "موجودی کل حساب‌های شخصی")
                                Text(formatToman(totalBalance, hideAmounts), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("درآمد: " + formatToman(totalIncome, hideAmounts), color = Color(0xFF10B981))
                                    Text("هزینه: " + formatToman(totalExpense, hideAmounts), color = Color(0xFFF43F5E))
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
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("هنوز حسابی نساخته‌اید!")
                                    Button(onClick = { showAddAccDialog = true }) { Text("ساخت اولین حساب") }
                                }
                            }
                        }
                    } else {
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                accounts.forEach { acc ->
                                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))) {
                                        Column(Modifier.padding(14.dp).width(170.dp)) {
                                            Text(acc.name, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(6.dp))
                                            Text(formatToman(acc.balanceRials, hideAmounts), color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Text("لیست تراکنش‌ها", fontWeight = FontWeight.Bold) }

                    items(filteredTxs) { tx ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(tx.category + " (" + tx.type.titleFa + ")", fontWeight = FontWeight.Bold)
                                    if (tx.description.isNotBlank()) Text(tx.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    formatToman(tx.amountRials, hideAmounts),
                                    color = when (tx.type) {
                                        TxType.INCOME -> Color(0xFF10B981)
                                        TxType.EXPENSE -> Color(0xFFF43F5E)
                                        TxType.TRANSFER -> Color(0xFF38BDF8)
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (showAddAccDialog) {
                var name by remember { mutableStateOf("") }
                var initBal by remember { mutableStateOf("") }
                var isBiz by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showAddAccDialog = false },
                    title = { Text("ایجاد حساب جدید") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام حساب (مثلاً بانک ملت)") })
                            OutlinedTextField(value = initBal, onValueChange = { initBal = it }, label = { Text("موجودی اولیه (تومان)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isBiz, onCheckedChange = { isBiz = it })
                                Text("حساب کسب‌وکار است")
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (name.isNotBlank()) {
                                val rials = (initBal.toLongOrNull() ?: 0L) * 10L
                                scope.launch { dao.insertAccount(AccountEntity(name = name, balanceRials = rials, isBusiness = isBiz)) }
                                showAddAccDialog = false
                            }
                        }) { Text("ذخیره") }
                    }
                )
            }

            if (showAddTxSheet && accounts.isNotEmpty()) {
                var amountStr by remember { mutableStateOf("") }
                var desc by remember { mutableStateOf("") }
                var cat by remember { mutableStateOf("عمومی") }
                var txType by remember { mutableStateOf(TxType.EXPENSE) }
                var selectedAccId by remember { mutableLongStateOf(accounts.first().id) }
                var targetAccId by remember { mutableLongStateOf(accounts.last().id) }

                AlertDialog(
                    onDismissRequest = { showAddTxSheet = false },
                    title = { Text("ثبت سریع تراکنش") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TxType.entries.forEach { t ->
                                    FilterChip(selected = txType == t, onClick = { txType = t }, label = { Text(t.titleFa) })
                                }
                            }
                            OutlinedTextField(value = amountStr, onValueChange = { amountStr = it }, label = { Text("مبلغ (تومان)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(value = cat, onValueChange = { cat = it }, label = { Text("دسته‌بندی (مثلاً خوراک، حقوق)") })
                            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("توضیحات") })
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val rials = (amountStr.toLongOrNull() ?: 0L) * 10L
                            if (rials > 0) {
                                val acc = accounts.find { it.id == selectedAccId }
                                scope.launch {
                                    dao.recordTransaction(
                                        TxEntity(
                                            amountRials = rials,
                                            type = txType,
                                            category = cat,
                                            description = desc,
                                            accountId = selectedAccId,
                                            targetAccountId = if (txType == TxType.TRANSFER) targetAccId else null,
                                            isBusiness = acc?.isBusiness ?: false
                                        )
                                    )
                                }
                                showAddTxSheet = false
                            }
                        }) { Text("ثبت نهایی") }
                    }
                )
            }
        }
    }
}
