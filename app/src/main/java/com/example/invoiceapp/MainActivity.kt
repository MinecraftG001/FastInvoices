package com.example.invoiceapp
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- GENERALIZED DATA STRUCTURES ---
data class BusinessProfile(val name: String, val phone: String, val address: String)
data class Customer(val name: String, val address: String)
data class Good(val name: String, val prices: Map<String, String>) // Dynamic prices mapped to unit names
data class InvoiceItem(val good: Good, val size: String, val qty: Int, val rate: Double?) {
    val amount: Double? get() = if (rate != null) qty * rate else null
}

// --- UNIVERSAL STORAGE ENGINE ---
object StorageHelper {
    fun saveProfile(context: Context, profile: BusinessProfile) {
        val prefs = context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).edit()
        prefs.putString("biz_name", profile.name)
        prefs.putString("biz_phone", profile.phone)
        prefs.putString("biz_address", profile.address)
        prefs.apply()
    }

    fun loadProfile(context: Context): BusinessProfile {
        val prefs = context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE)
        return BusinessProfile(
            prefs.getString("biz_name", "") ?: "",
            prefs.getString("biz_phone", "") ?: "",
            prefs.getString("biz_address", "") ?: ""
        )
    }

    fun saveUnits(context: Context, units: List<String>) {
        context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).edit().putString("units", units.joinToString(";;")).apply()
    }

    fun loadUnits(context: Context): List<String> {
        val str = context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).getString("units", "") ?: ""
        if (str.isEmpty()) return listOf("Piece", "Kg", "Box") // Universal defaults
        return str.split(";;")
    }

    fun saveCustomers(context: Context, list: List<Customer>) {
        val str = list.joinToString(";;") { "${it.name}|${it.address}" }
        context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).edit().putString("customers", str).apply()
    }

    fun loadCustomers(context: Context): List<Customer> {
        val str = context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).getString("customers", "") ?: ""
        if (str.isEmpty()) return emptyList()
        return str.split(";;").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) Customer(parts[0], parts[1]) else null
        }
    }

    fun saveGoods(context: Context, list: List<Good>) {
        val str = list.joinToString(";;") { good ->
            val mapStr = good.prices.entries.joinToString("~") { "${it.key}=${it.value}" }
            "${good.name}|$mapStr"
        }
        context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).edit().putString("goods", str).apply()
    }

    fun loadGoods(context: Context): List<Good> {
        val str = context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).getString("goods", "") ?: ""
        if (str.isEmpty()) return emptyList()
        return str.split(";;").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.isNotEmpty()) {
                val name = parts[0]
                val prices = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].split("~").associate { kv ->
                        val pair = kv.split("=")
                        if (pair.size == 2) pair[0] to pair[1] else "" to ""
                    }.filterKeys { it.isNotEmpty() }
                } else emptyMap()
                Good(name, prices)
            } else null
        }
    }

    fun getNextInvoiceNumber(context: Context): Int {
        return context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).getInt("invoice_no", 101)
    }

    fun incrementInvoiceNumber(context: Context) {
        val current = getNextInvoiceNumber(context)
        context.getSharedPreferences("InvoiceApp", Context.MODE_PRIVATE).edit().putInt("invoice_no", current + 1).apply()
    }
}

fun saveInvoiceToGallery(context: Context, bitmap: android.graphics.Bitmap, invoiceNo: String, bizName: String) {
    val cleanBizName = bizName.replace(Regex("[^A-Za-z0-9]"), "")
    val resolver = context.contentResolver
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "${cleanBizName}_Invoice_$invoiceNo.jpg")
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Invoices")
    }
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, stream)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F8FA)) {
                    val context = LocalContext.current

                    var businessProfile by remember { mutableStateOf(StorageHelper.loadProfile(context)) }
                    var currentScreen by remember { mutableStateOf(if (businessProfile.name.isEmpty()) "Onboarding" else "Home") }

                    var selectedCustomer by remember { mutableStateOf(Customer("", "")) }
                    var selectedDate by remember { mutableStateOf("Today") }
                    var invoiceNumber by remember { mutableIntStateOf(StorageHelper.getNextInvoiceNumber(context)) }

                    val invoiceItems = remember { mutableStateListOf<InvoiceItem>() }
                    val customerList = remember { mutableStateListOf(*StorageHelper.loadCustomers(context).toTypedArray()) }
                    val goodsList = remember { mutableStateListOf(*StorageHelper.loadGoods(context).toTypedArray()) }
                    val unitList = remember { mutableStateListOf(*StorageHelper.loadUnits(context).toTypedArray()) }

                    when (currentScreen) {
                        "Onboarding" -> OnboardingScreen(
                            onComplete = { profile ->
                                StorageHelper.saveProfile(context, profile)
                                businessProfile = profile
                                currentScreen = "Home"
                            }
                        )
                        "Home" -> HomeScreen(
                            businessName = businessProfile.name,
                            onNavigateToInvoice = {
                                invoiceItems.clear()
                                invoiceNumber = StorageHelper.getNextInvoiceNumber(context)
                                currentScreen = "CustomerSelect"
                            }
                        )
                        "CustomerSelect" -> CustomerSelectScreen(
                            customers = customerList,
                            onCustomerSelected = { customer ->
                                selectedCustomer = customer
                                currentScreen = "InvoiceEditor"
                            },
                            onNavigateBack = { currentScreen = "Home" },
                            onNavigateToGoods = { currentScreen = "ManageGoods" },
                            onNavigateToCustomers = { currentScreen = "ManageCustomers" },
                            onNavigateToUnits = { currentScreen = "ManageUnits" }
                        )
                        "InvoiceEditor" -> InvoiceEditorScreen(
                            customer = selectedCustomer,
                            goodsList = goodsList,
                            unitList = unitList,
                            invoiceItems = invoiceItems,
                            selectedDate = selectedDate,
                            onDateChanged = { newDate -> selectedDate = newDate },
                            onNavigateBack = { currentScreen = "CustomerSelect" },
                            onNavigateToPreview = { currentScreen = "Preview" }
                        )
                        "Preview" -> InvoicePreviewScreen(
                            profile = businessProfile,
                            customer = selectedCustomer,
                            date = selectedDate,
                            invoiceNumber = invoiceNumber.toString(),
                            invoiceItems = invoiceItems,
                            onNavigateBack = { currentScreen = "InvoiceEditor" },
                            onSaveJpg = {
                                StorageHelper.incrementInvoiceNumber(context)
                                currentScreen = "Home"
                            }
                        )
                        "ManageUnits" -> UnitManagerScreen(
                            unitList = unitList,
                            onSave = { newUnit ->
                                unitList.add(newUnit)
                                StorageHelper.saveUnits(context, unitList)
                            },
                            onDelete = { unitToDelete ->
                                unitList.remove(unitToDelete)
                                StorageHelper.saveUnits(context, unitList)
                            },
                            onNavigateBack = { currentScreen = "CustomerSelect" }
                        )
                        "ManageGoods" -> GoodsManagerScreen(
                            goodsList = goodsList,
                            unitList = unitList,
                            onSave = { newGood ->
                                goodsList.add(newGood)
                                StorageHelper.saveGoods(context, goodsList)
                            },
                            onDelete = { goodToDelete ->
                                goodsList.remove(goodToDelete)
                                StorageHelper.saveGoods(context, goodsList)
                            },
                            onNavigateBack = { currentScreen = "CustomerSelect" }
                        )
                        "ManageCustomers" -> CustomerManagerScreen(
                            customerList = customerList,
                            onSave = { newCustomer ->
                                customerList.add(newCustomer)
                                StorageHelper.saveCustomers(context, customerList)
                            },
                            onDelete = { custToDelete ->
                                customerList.remove(custToDelete)
                                StorageHelper.saveCustomers(context, customerList)
                            },
                            onNavigateBack = { currentScreen = "CustomerSelect" }
                        )
                    }
                }
            }
        }
    }
}

// --- NEW: ONBOARDING SCREEN ---
@Composable
fun OnboardingScreen(onComplete: (BusinessProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to FastInvoice", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF26A69A))
        Text("Let's set up your business profile. You only have to do this once.", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Business Name") }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Contact Number") }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Full Address / Tagline") }, modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp))

        Button(
            onClick = { if (name.isNotBlank()) onComplete(BusinessProfile(name, phone, address)) },
            modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(Color(0xFF2D3748))
        ) { Text("Save & Continue", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun HomeScreen(businessName: String, onNavigateToInvoice: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF26A69A)), contentAlignment = Alignment.Center) {
            Text(businessName.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(businessName.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2D3748), modifier = Modifier.padding(bottom = 60.dp), textAlign = TextAlign.Center)
        Button(onClick = { onNavigateToInvoice() }, modifier = Modifier.fillMaxWidth(0.8f).height(70.dp), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF26A69A))) { Text("📝  Create Invoice", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { Toast.makeText(context, "GST Feature Coming Soon!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth(0.8f).height(70.dp), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(4.dp), colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE))) { Text("🔒  GST (Coming Soon)", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun CustomerSelectScreen(customers: List<Customer>, onCustomerSelected: (Customer) -> Unit, onNavigateBack: () -> Unit, onNavigateToGoods: () -> Unit, onNavigateToCustomers: () -> Unit, onNavigateToUnits: () -> Unit) {
    BackHandler { onNavigateBack() }
    var showMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredCustomers = customers.filter { it.name.contains(searchQuery, ignoreCase = true) || it.address.contains(searchQuery, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Select Customer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
            Box {
                Text("⚙️ Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showMenu = true }.padding(8.dp))
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(Color.White)) {
                    DropdownMenuItem(text = { Text("📦 Manage Goods", fontSize = 16.sp) }, onClick = { showMenu = false; onNavigateToGoods() })
                    DropdownMenuItem(text = { Text("⚖️ Manage Quantity Units", fontSize = 16.sp) }, onClick = { showMenu = false; onNavigateToUnits() })
                    DropdownMenuItem(text = { Text("👥 Manage Customers", fontSize = 16.sp) }, onClick = { showMenu = false; onNavigateToCustomers() })
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("🔍 Search...") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            items(filteredCustomers) { customer ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onCustomerSelected(customer) }, colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(customer.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                        Text(customer.address, fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- NEW: UNIT MANAGER SCREEN ---
@Composable
fun UnitManagerScreen(unitList: List<String>, onSave: (String) -> Unit, onDelete: (String) -> Unit, onNavigateBack: () -> Unit) {
    BackHandler { onNavigateBack() }
    var newUnit by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { onNavigateBack() }, colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE))) { Text("⬅ Back") }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Manage Custom Quantities", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = newUnit, onValueChange = { newUnit = it }, label = { Text("Unit Name (e.g. Liter, Dozen)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (newUnit.isNotBlank() && !unitList.contains(newUnit)) { onSave(newUnit); newUnit = "" } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF26A69A))) { Text("Save Unit") }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Saved Units", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(unitList) { unit ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(unit, fontWeight = FontWeight.Bold)
                    Text("🗑️", fontSize = 20.sp, modifier = Modifier.clickable { onDelete(unit) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
fun CustomerManagerScreen(customerList: List<Customer>, onSave: (Customer) -> Unit, onDelete: (Customer) -> Unit, onNavigateBack: () -> Unit) {
    BackHandler { onNavigateBack() }
    var newName by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { onNavigateBack() }, colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE))) { Text("⬅ Back") }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Add Customer", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = newAddress, onValueChange = { newAddress = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (newName.isNotBlank()) { onSave(Customer(newName, newAddress)); newName = ""; newAddress = "" } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF26A69A))) { Text("Save") }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Saved Customers", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(customerList) { cust ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(cust.name, fontWeight = FontWeight.Bold)
                        Text(cust.address, fontSize = 12.sp, color = Color.Gray)
                    }
                    Text("🗑️", fontSize = 20.sp, modifier = Modifier.clickable { onDelete(cust) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
fun GoodsManagerScreen(goodsList: List<Good>, unitList: List<String>, onSave: (Good) -> Unit, onDelete: (Good) -> Unit, onNavigateBack: () -> Unit) {
    BackHandler { onNavigateBack() }
    var newGoodName by remember { mutableStateOf("") }

    // Dynamic price tracker based on the user's custom units
    val priceMap = remember { mutableStateMapOf<String, String>() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { onNavigateBack() }, colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE))) { Text("⬅ Back") }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Add Good & Standard Prices", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = newGoodName, onValueChange = { newGoodName = it }, label = { Text("Product Name") }, modifier = Modifier.fillMaxWidth())

        // Dynamically generated text boxes for every custom unit
        LazyColumn(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            items(unitList) { unit ->
                OutlinedTextField(
                    value = priceMap[unit] ?: "",
                    onValueChange = { priceMap[unit] = it },
                    label = { Text("$unit Price (Optional)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        Button(
            onClick = {
                if (newGoodName.isNotBlank()) {
                    onSave(Good(newGoodName, priceMap.toMap()))
                    newGoodName = ""; priceMap.clear()
                }
            },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(Color(0xFF26A69A))
        ) { Text("Save Good") }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Saved Goods", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(goodsList) { good ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(good.name, fontWeight = FontWeight.Bold)
                        val priceString = good.prices.entries.filter { it.value.isNotBlank() }.joinToString(" | ") { "${it.key}: ₹${it.value}" }
                        Text(if(priceString.isNotEmpty()) priceString else "No standard prices", fontSize = 12.sp, color = Color.Gray)
                    }
                    Text("🗑️", fontSize = 20.sp, modifier = Modifier.clickable { onDelete(good) }.padding(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceEditorScreen(
    customer: Customer,
    goodsList: List<Good>,
    unitList: List<String>,
    invoiceItems: MutableList<InvoiceItem>,
    selectedDate: String,
    onDateChanged: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToPreview: () -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    var selectedGood by remember { mutableStateOf<Good?>(null) }
    var showGoodsDropdown by remember { mutableStateOf(false) }
    var selectedSize by remember { mutableStateOf("") }
    var showSizeDropdown by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    LaunchedEffect(selectedSize, selectedGood) {
        if (selectedGood != null && selectedSize.isNotBlank()) {
            price = selectedGood!!.prices[selectedSize] ?: ""
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
                if (selectedDate == "Custom") onDateChanged("Today")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    datePickerState.selectedDateMillis?.let { millis ->
                        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        onDateChanged(formatter.format(Date(millis)))
                    }
                }) { Text("OK", color = Color(0xFF26A69A)) }
            }
        ) { DatePicker(state = datePickerState) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onNavigateBack() }, colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE))) { Text("⬅ Back") }
                Text(customer.name, fontWeight = FontWeight.Bold, color = Color(0xFF26A69A), fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("1. Date", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Today", "Tomorrow", "Custom").forEach { dateOpt ->
                    val isSelected = selectedDate == dateOpt || (dateOpt == "Custom" && selectedDate !in listOf("Today", "Tomorrow"))
                    FilterChip(selected = isSelected, onClick = { if (dateOpt == "Custom") showDatePicker = true else onDateChanged(dateOpt) }, label = { Text(if (dateOpt == "Custom" && selectedDate !in listOf("Today", "Tomorrow")) selectedDate else dateOpt) })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("2. Select Product", fontWeight = FontWeight.Bold)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = selectedGood?.name ?: "", onValueChange = {}, readOnly = true, placeholder = { Text("Tap to select product...") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) })
                Box(modifier = Modifier.matchParentSize().clickable { showGoodsDropdown = true })
                DropdownMenu(expanded = showGoodsDropdown, onDismissRequest = { showGoodsDropdown = false }) {
                    goodsList.forEach { good -> DropdownMenuItem(text = { Text(good.name) }, onClick = { selectedGood = good; showGoodsDropdown = false }) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Replaced Chips with a dynamic dropdown to handle infinite custom units cleanly
            Text("3. Unit Size", fontWeight = FontWeight.Bold)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = selectedSize, onValueChange = {}, readOnly = true, placeholder = { Text("Select quantity type...") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) })
                Box(modifier = Modifier.matchParentSize().clickable { showSizeDropdown = true })
                DropdownMenu(expanded = showSizeDropdown, onDismissRequest = { showSizeDropdown = false }) {
                    unitList.forEach { unit -> DropdownMenuItem(text = { Text(unit) }, onClick = { selectedSize = unit; showSizeDropdown = false }) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("4. Details", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Qty (e.g. 5)") }, modifier = Modifier.weight(1f).padding(end = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Rate (Optional)") }, modifier = Modifier.weight(1f).padding(start = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val q = quantity.toIntOrNull()
                    val r = price.toDoubleOrNull()

                    if (selectedGood != null && selectedSize.isNotBlank() && q != null) {
                        val itemExists = invoiceItems.any { it.good.name == selectedGood!!.name && it.size == selectedSize }
                        if (itemExists) {
                            Toast.makeText(context, "Error: ${selectedGood!!.name} (${selectedSize}) is already added!", Toast.LENGTH_LONG).show()
                        } else {
                            invoiceItems.add(InvoiceItem(selectedGood!!, selectedSize, q, r))
                            selectedGood = null; selectedSize = ""; quantity = ""; price = ""
                        }
                    } else { Toast.makeText(context, "Please ensure product, size, and Qty are filled", Toast.LENGTH_SHORT).show() }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(Color(0xFF2D3748))
            ) { Text("➕ Add Item to Invoice", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (invoiceItems.isNotEmpty()) {
            item { Text("Added Items", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF26A69A), modifier = Modifier.padding(bottom = 8.dp)) }
            items(invoiceItems) { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${item.good.name} (${item.size})", fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))

                        val rateDisplay = if (item.rate != null) "₹${item.rate}" else "(No Rate)"
                        val amtDisplay = if (item.amount != null) "₹${item.amount}" else "-"
                        Text("${item.qty} x $rateDisplay = $amtDisplay", fontSize = 14.sp, color = Color.Gray)
                    }
                    Text("❌", fontSize = 18.sp, modifier = Modifier.clickable { invoiceItems.remove(item) }.padding(8.dp))
                }
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { onNavigateToPreview() }, modifier = Modifier.fillMaxWidth().height(70.dp), shape = RoundedCornerShape(12.dp), elevation = ButtonDefaults.buttonElevation(8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF26A69A))) {
                    Text("👀  Preview Invoice", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// --- GENERALIZED INVOICE REPLICA ---
@Composable
fun InvoicePreviewScreen(
    profile: BusinessProfile,
    customer: Customer,
    date: String,
    invoiceNumber: String,
    invoiceItems: List<InvoiceItem>,
    onNavigateBack: () -> Unit,
    onSaveJpg: () -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    val view = LocalView.current

    var invoiceBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val grandTotal = invoiceItems.mapNotNull { it.amount }.sum()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .border(2.dp, Color.Black)
                .onGloballyPositioned { coordinates -> invoiceBounds = coordinates.boundsInRoot() }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(text = "DELIVERY CHALLAN", modifier = Modifier.fillMaxWidth().padding(6.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.Black)
                Divider(color = Color.Black, thickness = 2.dp)

                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Column(modifier = Modifier.weight(1f).padding(6.dp)) {
                        Text("FROM :", fontSize = 9.sp, color = Color.Gray)
                        Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Black)
                        Text("Ph: ${profile.phone}", fontSize = 11.sp, color = Color.Black)
                    }
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(2.dp))
                    Column(modifier = Modifier.weight(1f).padding(6.dp)) {
                        Text("TO :", fontSize = 9.sp, color = Color.Gray)
                        Text(customer.name, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Black)
                        Text(customer.address, fontSize = 11.sp, color = Color.Black)
                    }
                }
                Divider(color = Color.Black, thickness = 2.dp)

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f).padding(6.dp)) {
                        Text("Invoice No. : $invoiceNumber", fontSize = 10.sp, color = Color.Black)
                    }
                    Divider(color = Color.Black, modifier = Modifier.height(25.dp).width(2.dp))
                    Column(modifier = Modifier.weight(1f).padding(6.dp)) {
                        val printDate = if(date == "Today") SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()) else date
                        Text("DATE : $printDate", fontSize = 10.sp, color = Color.Black)
                    }
                }
                Divider(color = Color.Black, thickness = 2.dp)

                Text(
                    text = profile.address.ifBlank { "PLEASE RECEIVE THE FOLLOWING GOODS IN GOOD ORDER & CONDITION." },
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = Color.Black
                )
                Divider(color = Color.Black, thickness = 2.dp)

                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Color(0xFFE0E0E0))) {
                    Text("Quantity", modifier = Modifier.weight(1.5f).padding(4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                    Text("PARTICULARS", modifier = Modifier.weight(4f).padding(4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                    Text("Rate", modifier = Modifier.weight(1.5f).padding(4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                    Text("Amount ₹.", modifier = Modifier.weight(2f).padding(4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                }
                Divider(color = Color.Black, thickness = 2.dp)

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(invoiceItems) { item ->
                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            Text("${item.qty}x${item.size}", modifier = Modifier.weight(1.5f).padding(4.dp), textAlign = TextAlign.Center, fontSize = 10.sp, color = Color.Black)
                            Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                            Text(item.good.name, modifier = Modifier.weight(4f).padding(4.dp), fontSize = 10.sp, color = Color.Black)
                            Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                            Text(item.rate?.toString() ?: "", modifier = Modifier.weight(1.5f).padding(4.dp), textAlign = TextAlign.Center, fontSize = 10.sp, color = Color.Black)
                            Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(1.dp))
                            Text(item.amount?.toString() ?: "", modifier = Modifier.weight(2f).padding(4.dp), textAlign = TextAlign.End, fontSize = 10.sp, color = Color.Black)
                        }
                        Divider(color = Color.LightGray, thickness = 1.dp)
                    }
                }

                Divider(color = Color.Black, thickness = 2.dp)
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Spacer(modifier = Modifier.weight(7f))
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(2.dp))
                    Text("Total", modifier = Modifier.weight(1.5f).padding(4.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                    Divider(color = Color.Black, modifier = Modifier.fillMaxHeight().width(2.dp))

                    val totalDisplay = if (grandTotal > 0) grandTotal.toString() else ""
                    Text(totalDisplay, modifier = Modifier.weight(2f).padding(4.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { onNavigateBack() }, modifier = Modifier.weight(1f).height(60.dp).padding(end = 8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF90A4AE)), shape = RoundedCornerShape(12.dp)) { Text("⬅ Go Back", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = {
                    try {
                        val fullBitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(fullBitmap)
                        view.draw(canvas)

                        invoiceBounds?.let { bounds ->
                            val croppedBitmap = android.graphics.Bitmap.createBitmap(fullBitmap, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                            saveInvoiceToGallery(context, croppedBitmap, invoiceNumber, profile.name)
                            fullBitmap.recycle(); croppedBitmap.recycle()

                            Toast.makeText(context, "Invoice #$invoiceNumber saved to Gallery! 🎉", Toast.LENGTH_LONG).show()
                            onSaveJpg()
                        }
                    } catch(e: Exception) {
                        Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f).height(60.dp).padding(start = 8.dp), colors = ButtonDefaults.buttonColors(Color(0xFF26A69A)), shape = RoundedCornerShape(12.dp)
            ) { Text("💾 Save JPG", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}