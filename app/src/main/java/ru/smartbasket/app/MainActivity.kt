package ru.smartbasket.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val green = Color.rgb(23,107,74)
    private val ink = Color.rgb(27,40,34)
    private val gray = Color.rgb(88,105,96)
    private val bg = Color.rgb(244,247,244)
    private val prefs by lazy { getSharedPreferences("basket", MODE_PRIVATE) }
    private var items = listOf<BasketItem>()
    private var budget = 300000L
    private var delivery = 0L
    private var portions = 3
    private var tab = 0
    private var guided = false
    private val reviewed = mutableSetOf<String>()
    private lateinit var body: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load(); render(); acceptIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            i.action = null
            if (text.isNotBlank()) importDialog(text)
        }
    }
    private fun load() {
        budget = prefs.getLong("budget",300000).coerceIn(0,100_000_000)
        delivery = prefs.getLong("delivery",0).coerceIn(0,100_000_000)
        portions = prefs.getInt("portions",3).coerceIn(1,100)
        try {
            val arr = JSONArray(prefs.getString("items","[]"))
            items = (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                BasketItem(o.getString("name"),o.getLong("amount"),o.getString("unit"),o.getLong("home"),o.getLong("pack"),if(o.isNull("price")) null else o.getLong("price"),o.optBoolean("checked"))
            }
        } catch (_: Exception) { items = emptyList(); Toast.makeText(this,"Не удалось прочитать сохранённый список",Toast.LENGTH_LONG).show() }
    }
    private fun save() {
        val a=JSONArray()
        for (i in items) a.put(JSONObject().put("name",i.name).put("amount",i.amount).put("unit",i.unit).put("home",i.atHome).put("pack",i.pack).put("price",i.price ?: JSONObject.NULL).put("checked",i.checked))
        prefs.edit().putLong("budget",budget).putLong("delivery",delivery).putInt("portions",portions).putString("items",a.toString()).apply()
    }
    private fun update() { save(); render() }
    private fun rounded(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun vertical() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun label(s: String, size: Float=16f, color: Int=ink, bold: Boolean=false) = TextView(this).apply { text=s; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun gap(parent: LinearLayout, height: Int=10) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun card(parent: LinearLayout): LinearLayout = vertical().apply { background=rounded(Color.WHITE); setPadding(dp(16),dp(14),dp(16),dp(14)); parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) }) }
    private fun button(s: String, primary: Boolean=false, action: ()->Unit) = Button(this).apply { text=s; textSize=14f; isAllCaps=false; setTextColor(if(primary) Color.WHITE else green); background=rounded(if(primary)green else Color.rgb(227,240,232),12); minHeight=dp(48); minimumHeight=dp(48); setPadding(dp(12),dp(8),dp(12),dp(8)); setOnClickListener { action() } }
    private fun addButton(p: LinearLayout, s:String, primary:Boolean=false, action:()->Unit) { p.addView(button(s,primary,action),LinearLayout.LayoutParams(-1,dp(49)).apply { topMargin=dp(8) }) }
    private fun render() {
        val root=vertical().apply { setBackgroundColor(bg); setPadding(dp(16),dp(10),dp(16),dp(8)) }
        root.addView(label("Умная корзина",26f,green,true))
        root.addView(label("Планирование покупок • локальная версия",13f,gray))
        val tabs=horizontal()
        listOf("Корзина","Рецепты","Магазин").forEachIndexed { i,s -> tabs.addView(button(s,tab==i){ tab=i; render() },LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(2),dp(8),dp(2),dp(10)) }) }
        root.addView(tabs)
        val sc=ScrollView(this).apply { isFillViewport=true }
        body=vertical(); sc.addView(body); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        when(tab){ 0->basketScreen(); 1->recipeScreen(); else->storeScreen() }
    }
    private fun basketScreen() {
        val s=summarize(items,budget,delivery)
        val c=card(body)
        c.addView(label("Бюджет",14f,gray)); c.addView(label(rub(budget),29f,green,true))
        c.addView(label((if(s.complete) "Итого" else "Известный минимум")+": "+rub(s.totalKnown),20f,if(s.over) Color.rgb(175,46,32) else ink,true))
        c.addView(label(when { s.over -> "Превышение бюджета минимум на ${rub(s.totalKnown-budget)}"; !s.complete -> "Без цены/фасовки: ${s.unknown}. Бюджет пока не подтверждён."; else -> "Остаток бюджета: ${rub(budget-s.totalKnown)}" },14f,gray))
        c.addView(label("Доставка (введено вручную): ${rub(delivery)}",13f,gray))
        addButton(c,"Бюджет и доставка") { budgetDialog() }
        addButton(c,"Добавить продукт",true) { addDialog() }
        addButton(c,"Импорт списка из ChatGPT") { importDialog("") }
        if(items.any { it.need>0 && !it.checked }) addButton(c,"Собрать покупки по списку",true) { startGuided() }
        if(items.isEmpty()) { val empty=card(body); empty.addView(label("Пока нет покупок",18f,ink,true)); empty.addView(label("Выбери рецепты или добавь свои продукты.",14f,gray)); addButton(empty,"Выбрать рецепты") { tab=1;render() }; return }
        val progress = label("Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}",19f,ink,true)
        body.addView(progress)
        items.forEachIndexed { index,i ->
            val item=card(body)
            val check=CheckBox(this).apply { text=i.name; textSize=17f; setTextColor(ink); isChecked=i.checked; setOnCheckedChangeListener { _,v -> items=items.toMutableList().also { it[index]=i.copy(checked=v) }; save(); progress.text="Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}" } }
            item.addView(check)
            item.addView(label("Нужно: ${i.amount} ${i.unit} · дома: ${i.atHome} · купить: ${i.need} ${i.unit}",14f,gray))
            val cost=i.cost
            item.addView(label(if(i.need==0L) "Есть дома" else if(cost==null) "Цена или фасовка не указана" else "${i.packs} уп. × ${rub(i.price!!)} = ${rub(cost)}",15f,green,true))
            val buttons=horizontal()
            buttons.addView(button("Цена / количество") { editDialog(index) },LinearLayout.LayoutParams(0,dp(48),1f))
            buttons.addView(button("В магазин") { openStore(index) },LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(6)})
            item.addView(buttons)
        }
        addButton(body,"Поделиться списком") { share() }
        addButton(body,"Очистить список") { AlertDialog.Builder(this).setTitle("Удалить все покупки?").setNegativeButton("Отмена",null).setPositiveButton("Очистить"){_,_->items=emptyList();update()}.show() }
    }
    private fun recipeScreen() {
        val top=card(body); top.addView(label("Порции на блюдо: $portions",20f,ink,true)); addButton(top,"Изменить число порций"){ val e=field(portions.toString(),"1–100",true); dialog("Число порций",e){ val n=e.text.toString().toIntOrNull(); if(n==null||n !in 1..100) false else {portions=n;update();true} } }
        for(r in recipes){ val c=card(body); c.addView(label(r.name,19f,ink,true)); c.addView(label(r.ingredients.joinToString(" · "){"${it.name} ${(it.amount*portions+r.servings-1)/r.servings} ${it.unit}"},14f,gray)); c.addView(label(r.method,14f,ink)); addButton(c,"Добавить на $portions порц.",true){ try { items=merge(items,r.ingredients,portions,r.servings); save(); toast("Рецепт добавлен") }catch(e:Exception){toast(e.message?:"Ошибка количества")} } }
        addButton(body,"Перейти в корзину") { tab=0;render() }
    }
    private fun storeScreen() {
        val c=card(body); c.addView(label("Пятёрочка",23f,green,true)); c.addView(label("Встроенный браузер открывает официальный сайт. Войди через X5ID, выбери адрес доставки. Сессия хранится в WebView на телефоне.",16f)); addButton(c,"Открыть магазин в приложении",true){openStore()}; addButton(c,"Скопировать список покупок"){copy(listText())}; c.addView(label("Для конкретного ингредиента нажми «В магазин», выбери товар и перенеси цену и фасовку. Добавление и проверка корзины выполняются на сайте. Режим экспериментальный.",14f,gray))
        val help=card(body); help.addView(label("Передача из ChatGPT",19f,ink,true)); help.addView(label("Попроси ChatGPT вернуть список строк в формате: название;количество;г/мл/шт. Например: Молоко;500;мл. Передай текст через «Поделиться» (если доступно) или скопируй и вставь в импорт.",15f)); addButton(help,"Скопировать шаблон запроса"){copy("Составь список ингредиентов для выбранных рецептов. Верни только строки в формате название;количество;единица (г, мл или шт). Без цен, заголовков и нумерации.")}
    }
    private fun field(value:String="",hint:String="", numeric:Boolean=false, decimal:Boolean=false) = EditText(this).apply { setText(value); this.hint=hint; inputType=if(numeric) InputType.TYPE_CLASS_NUMBER or (if(decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) else InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
    private fun fields(vararg pairs: Pair<String,EditText>): LinearLayout = vertical().apply { setPadding(dp(20),dp(8),dp(20),dp(12)); for((s,e) in pairs){addView(label(s,14f,gray));addView(e)} }
    private fun dialog(title:String,view:View,action:()->Boolean) {
        val d=AlertDialog.Builder(this).setTitle(title).setView(view).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(action()) d.dismiss() else toast("Проверь введённые данные") } }; d.show()
    }
    private fun budgetDialog() {
        val b=field("${budget/100}.${(budget%100).toString().padStart(2,'0')}",numeric=true,decimal=true);val d=field("${delivery/100}.${(delivery%100).toString().padStart(2,'0')}",numeric=true,decimal=true)
        dialog("Бюджет",fields("Лимит, ₽" to b,"Доставка и сборы, ₽" to d)){ val x=money(b.text.toString()); val y=money(d.text.toString()); if(x==null||y==null)false else{budget=x;delivery=y;update();true} }
    }
    private fun addDialog(){ val n=field(hint="Молоко");val q=field(hint="500",numeric=true);val u=field("мл","г, мл или шт");dialog("Добавить продукт",fields("Название" to n,"Количество" to q,"Единица" to u)){ val amount=q.text.toString().toLongOrNull();val unit=u.text.toString().trim();val name=n.text.toString().trim(); if(amount==null||amount !in 1..1_000_000||unit !in listOf("г","мл","шт")||name.isBlank()||name.length>120)false else try {items=merge(items,listOf(Ingredient(name,amount,unit)),1,1);update();true}catch(_:Exception){false} } }
    private fun editDialog(index:Int){val i=items[index];val q=field(i.amount.toString(),numeric=true);val h=field(i.atHome.toString(),numeric=true);val p=field(i.pack.takeIf{it>0}?.toString().orEmpty(),numeric=true);val v=field(i.price?.let{"${it/100}.${(it%100).toString().padStart(2,'0')}"}.orEmpty(),numeric=true,decimal=true)
        val form=fields("Нужно, ${i.unit}" to q,"Есть дома, ${i.unit}" to h,"Размер упаковки, ${i.unit}" to p,"Цена упаковки, ₽ (можно оставить пустой)" to v)
        val dlg=AlertDialog.Builder(this).setTitle(i.name).setView(form).setNegativeButton("Отмена",null).setNeutralButton("Удалить"){_,_->items=items.filterIndexed{n,_->n!=index};update()}.setPositiveButton("Сохранить",null).create()
        dlg.setOnShowListener {dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a=q.text.toString().toLongOrNull();val b=h.text.toString().toLongOrNull();val c=if(p.text.isBlank())0L else p.text.toString().toLongOrNull();val price=money(v.text.toString())
            if(a==null||a !in 1..10_000_000||b==null||b !in 0..10_000_000||c==null||c !in 0..10_000_000||(!v.text.isBlank()&&price==null))toast("Проверь количество и цену") else {items=items.toMutableList().also{it[index]=i.copy(amount=a,atHome=b,pack=c,price=price,checked=false)};update();dlg.dismiss()}
        }};dlg.show()
    }
    private fun importDialog(initial:String) {
        val e=EditText(this).apply{setText(initial);hint="Молоко;500;мл\nЯйца;6;шт";minLines=5;maxLines=10;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val wrap=vertical().apply{setPadding(dp(16),dp(6),dp(16),dp(6));addView(label("Одна строка на продукт: название;количество;г/мл/шт",14f,gray));addView(e)}
        dialog("Импорт покупок",wrap){ try {
            require(e.text.length<=50000);val lines=e.text.toString().lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList();require(lines.size in 1..200)
            val parsed=lines.map{ val p=it.split(';').map(String::trim);require(p.size==3 && p[0].length in 1..120);Ingredient(p[0],p[1].toLong(),p[2]) }
            val next=merge(items,parsed,1,1);items=next;tab=0;update();true
        }catch(_:Exception){false} }
    }
    private fun listText()=items.filter{it.need>0 && !it.checked}.joinToString("\n"){"${it.name} — ${it.need} ${it.unit}"}.ifEmpty{"Список пуст"}
    private fun share(){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,listText()),"Поделиться покупками"))}
    private fun copy(s:String){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Покупки",s));toast("Скопировано")}
    private fun startGuided() {
        if(summarize(items,budget,delivery).over){toast("Известные расходы уже выше бюджета. Измени цены или бюджет.");return}
        AlertDialog.Builder(this).setTitle("Сбор покупок").setMessage("Пройдём по непокупленным ингредиентам. Выбирай товары в магазине и подтверждай цену. Сбор не оплачивает заказ, не очищает существующую корзину и не считает товары добавленными без проверки.").setNegativeButton("Отмена",null).setPositiveButton("Начать"){_,_->
            reviewed.clear();guided=true;nextGuided()
        }.show()
    }
    private fun nextGuided() {
        val next=items.indexOfFirst{it.need>0&&!it.checked&&key(it.name,it.unit) !in reviewed}
        if(next<0){guided=false;toast("Список просмотрен. Сверь фактическую корзину и итог в магазине.");return}
        openStore(next)
    }
    private fun openStore(index:Int = -1) {
        val i=items.getOrNull(index)
        val intent=Intent(this,StoreActivity::class.java)
        if(i!=null) {
            val other=items.filterIndexed { n,_->n!=index }
            val summary=summarize(other,budget,delivery)
            intent.putExtra("name",i.name).putExtra("unit",i.unit).putExtra("need",i.need).putExtra("key",key(i.name,i.unit))
                .putExtra("room",budget-summary.totalKnown).putExtra("unknown",summary.unknown)
        }
        startActivityForResult(intent,701)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=701)return
        if(resultCode!=RESULT_OK||data==null){guided=false;return}
        val k=data.getStringExtra("key")?:return
        val pack=data.getLongExtra("pack",0)
        val price=data.getLongExtra("price",-1)
        if(pack !in 1..10_000_000||price !in 0..100_000_000)return
        val index=items.indexOfFirst{key(it.name,it.unit)==k}
        if(index<0)return
        items=items.toMutableList().also{it[index]=it[index].copy(pack=pack,price=price)}
        tab=0;update();toast("Цена и фасовка перенесены. Проверь корзину магазина.")
        if(guided) {
            reviewed.add(k)
            if(summarize(items,budget,delivery).over){guided=false;toast("Сбор остановлен: бюджет превышен.")}
            else nextGuided()
        }
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
}
