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
    private val updater by lazy { AppUpdater(this) }
    private var tab = 3
    private var days = 3
    private var promptDraft = ""
    private var currentPlan: MealPlan? = null
    private var guided = false
    private val reviewed = mutableSetOf<String>()
    private lateinit var body: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load(); render(); acceptIntent(intent)
        updater.check(false)
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
                BasketItem(o.getString("name"),o.getLong("amount"),o.getString("unit"),o.getLong("home"),o.getLong("pack"),if(o.isNull("price")) null else o.getLong("price"),o.optBoolean("checked"),o.optString("storeUrl"),o.optString("storeName"))
            }
        } catch (_: Exception) { items = emptyList(); Toast.makeText(this,"Не удалось прочитать сохранённый список",Toast.LENGTH_LONG).show() }
    }
    private fun save() {
        val a=JSONArray()
        for (i in items) a.put(JSONObject().put("name",i.name).put("amount",i.amount).put("unit",i.unit).put("home",i.atHome).put("pack",i.pack).put("price",i.price ?: JSONObject.NULL).put("checked",i.checked).put("storeUrl",i.storeUrl).put("storeName",i.storeName))
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
        listOf(3 to "Главная",1 to "Рецепты",0 to "Корзина",2 to "Магазин").forEach { (i,s) -> tabs.addView(button(s,tab==i){tab=i;render()},LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(2),dp(8),dp(2),dp(2)) }) }
        val sc=ScrollView(this).apply { isFillViewport=true }
        body=vertical(); sc.addView(body); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(tabs)
        setContentView(root)
        when(tab){ 0->basketScreen(); 1->recipeScreen(); 2->storeScreen(); else->homeScreen() }
    }
    private fun homeScreen() {
        val hero=card(body)
        hero.addView(label("Что будем готовить?",24f,ink,true))
        hero.addView(label("Меню и покупки в одном месте",15f,gray))
        val input=EditText(this).apply {
            setText(promptDraft);hint="Например: завтраки на 3 дня на 1500 ₽"
            minLines=3;maxLines=6;gravity=Gravity.TOP
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(bg,12)
            addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){promptDraft=s?.toString().orEmpty()}
                override fun afterTextChanged(s:android.text.Editable?){}
            })
        }
        hero.addView(input,LinearLayout.LayoutParams(-1,-2))
        addButton(hero,"Сказать голосом") { voice() }
        gap(hero)
        hero.addView(label("На сколько дней?",15f,gray,true))
        val presets=horizontal()
        listOf(1 to "Сегодня",3 to "3 дня",7 to "Неделя").forEach{(n,s)->presets.addView(button(s,days==n){days=n;render()},LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(2),0,dp(2),0)})}
        hero.addView(presets)
        hero.addView(label("Порций на блюдо",15f,gray,true))
        val people=horizontal()
        people.addView(button("−"){if(portions>1){portions--;save();render()}},LinearLayout.LayoutParams(0,dp(48),1f))
        people.addView(label("$portions",24f,ink,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(48),1f))
        people.addView(button("+"){if(portions<20){portions++;save();render()}},LinearLayout.LayoutParams(0,dp(48),1f))
        hero.addView(people)
        hero.addView(label("Бюджет",15f,gray,true))
        val b=field((budget/100).toString(),"₽",true,true)
        hero.addView(b)
        addButton(hero,"Составить меню и список",true) {
            try {
                val limit=money(b.text.toString())?:throw IllegalArgumentException("Укажи бюджет")
                val req=parseRequest(promptDraft,days,portions.coerceAtMost(20),limit)
                previewPlan(buildPlan(req))
            }catch(e:Exception){toast(e.message?:"Проверь запрос")}
        }
        hero.addView(label("Локальный подбор из встроенных рецептов. Цены магазина пока не известны.",13f,gray))
        val quick=card(body)
        quick.addView(label("Уже есть рецепт?",18f,ink,true))
        addButton(quick,"Вставить обычный список покупок"){importDialog("")}
        addButton(quick,"Выбрать блюда вручную"){tab=1;render()}
        if(items.isNotEmpty()) {
            val c=card(body);c.addView(label("Текущий список · ${items.size} поз.",18f,ink,true))
            val s=summarize(items,budget,delivery)
            c.addView(label(if(s.complete)rub(s.totalKnown) else "Цены ещё не проверены",15f,gray))
            addButton(c,"Продолжить покупки",true){tab=0;render()}
        }
    }
    private fun voice(){
        try{
            val i=Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,"ru-RU")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,"Что будем готовить?")
            startActivityForResult(i,702)
        }catch(_:Exception){toast("Распознавание речи недоступно на устройстве")}
    }
    private fun previewPlan(plan:MealPlan) {
        val proposed=plan.ingredients()
        val menu=plan.meals.groupBy{it.day}.entries.joinToString("\n\n"){(day,meals)->"День $day\n"+meals.joinToString("\n"){"${it.kind}: ${it.recipe.name}"}}
        val details="$menu\n\n${proposed.size} позиций · ${plan.portions} порц. · бюджет ${rub(plan.budget)}\nЦены не проверены. Это меню, а не подтверждённая корзина."
        val d=AlertDialog.Builder(this).setTitle("Предварительное меню").setMessage(details)
            .setNegativeButton("Назад",null).setPositiveButton(if(items.isEmpty())"Создать список" else "Заменить список"){_,_->
                val previous=items.associateBy{key(it.name,it.unit)}
                items=proposed.map{p->previous[key(p.name,p.unit)]?.let{p.copy(atHome=it.atHome,pack=it.pack,price=null)}?:p}
                budget=plan.budget;portions=plan.portions;days=plan.meals.maxOf{it.day};currentPlan=plan;tab=0;update()
            }
        if(items.isNotEmpty())d.setNeutralButton("Добавить к списку"){_,_->
            try{
                items=merge(items,proposed.map{Ingredient(it.name,it.amount,it.unit)},1,1)
                budget=plan.budget;portions=plan.portions;tab=0;update()
            }catch(e:Exception){toast(e.message?:"Не удалось добавить")}
        }
        d.show()
    }
    private fun basketScreen() {
        val s=summarize(items,budget,delivery)
        val c=card(body)
        c.addView(label("Бюджет",14f,gray)); c.addView(label(rub(budget),29f,green,true))
        c.addView(label((if(s.complete) "Итого" else "Известный минимум")+": "+rub(s.totalKnown),20f,if(s.over) Color.rgb(175,46,32) else ink,true))
        c.addView(label(when { s.over -> "Превышение бюджета минимум на ${rub(s.totalKnown-budget)}"; !s.complete -> "Без цены/фасовки: ${s.unknown}. Бюджет пока не подтверждён."; else -> "Остаток бюджета: ${rub(budget-s.totalKnown)}" },14f,gray))
        c.addView(label("Доставка (введено вручную): ${rub(delivery)}",13f,gray))
        if(items.any { it.need>0 && !it.checked }) {
            if(items.any { it.need>0 && !it.checked && it.storeUrl.isNotBlank() }) addButton(c,"Перенести товары в Пятёрочку",true){prepareTransfer()}
            c.addView(label(catalogLabel(),13f,gray))
            addButton(c,"Подобрать из загруженного каталога",true){chooseFromCatalog()}
            addButton(c,"Обновить весь каталог"){syncCatalog()}
            addButton(c,"Поиск по отдельным категориям"){batchQuote()}
            addButton(c,"Открывать товары по очереди"){startGuided()}
        }
        addButton(c,"Бюджет и доставка") { budgetDialog() }
        val tools=horizontal()
        tools.addView(button("＋ Продукт"){addDialog()},LinearLayout.LayoutParams(0,dp(48),1f))
        tools.addView(button("Вставить список"){importDialog("")},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(6)})
        c.addView(tools)
        currentPlan?.let { plan->addButton(c,"Посмотреть меню"){AlertDialog.Builder(this).setTitle("Меню").setMessage(plan.meals.joinToString("\n"){"День ${it.day} · ${it.kind}: ${it.recipe.name}"}).setPositiveButton("ОК",null).show()} }
        if(items.isEmpty()) { val empty=card(body); empty.addView(label("Пока нет покупок",18f,ink,true)); empty.addView(label("Выбери рецепты или добавь свои продукты.",14f,gray)); addButton(empty,"Выбрать рецепты") { tab=1;render() }; return }
        val progress = label("Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}",19f,ink,true)
        body.addView(progress)
        items.forEachIndexed { index,i ->
            val item=card(body)
            val check=CheckBox(this).apply { text=i.name; textSize=17f; setTextColor(ink); isChecked=i.checked; setOnCheckedChangeListener { _,v -> items=items.toMutableList().also { it[index]=i.copy(checked=v) }; save(); progress.text="Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}" } }
            item.addView(check)
            item.addView(label("Нужно: ${i.amount} ${i.unit} · дома: ${i.atHome} · купить: ${i.need} ${i.unit}",14f,gray))
            if(i.storeName.isNotBlank()) item.addView(label("Подобран: "+i.storeName,14f,green,true))
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
    private fun catalogLabel():String {
        val s=LocalCatalog(this).read()?:return "Каталог ещё не загружен"
        val date=java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.updated))
        return (if(s.source=="api")"API · " else "Сайт · ")+"Магазин "+s.store+" · "+s.entries.size+" товаров · "+date+" · полнота не подтверждена"
    }
    private fun syncCatalog(){startActivityForResult(Intent(this,CatalogSyncActivity::class.java),704)}
    private fun chooseFromCatalog(){
        val snapshot=LocalCatalog(this).read()
        if(snapshot==null){syncCatalog();return}
        if(System.currentTimeMillis()-snapshot.updated>6*60*60*1000L){toast("Каталог устарел. Обнови цены.");syncCatalog();return}
        AlertDialog.Builder(this).setTitle("Использовать загруженный каталог?")
            .setMessage(catalogLabel()+"\nПодтверди, что адрес доставки и магазин не менялись. Цены предварительные.")
            .setNegativeButton("Обновить"){_,_->syncCatalog()}
            .setPositiveButton("Подобрать"){_,_->
                var found=0
                items=items.map{i->
                    if(i.need<=0||i.checked)i else {
                        val m=findCatalogMatch(snapshot.entries,i)
                        if(m==null)i.copy(price=null,storeName="",storeUrl="")
                        else{found++;i.copy(pack=m.offer.pack,price=m.offer.price,storeName=m.offer.description,storeUrl=m.entry.url)}
                    }
                }
                tab=0;update();toast("Из каталога подобрано "+found+" позиций")
            }.show()
    }
    private fun catalogBrowser(){
        val snapshot=LocalCatalog(this).read()?:run{syncCatalog();return}
        val wrap=vertical().apply{setPadding(dp(12),dp(6),dp(12),dp(6))}
        val input=field(hint="Поиск по всему каталогу")
        val result=label("",14f,ink)
        wrap.addView(label(catalogLabel(),13f,gray));wrap.addView(input)
        val sc=ScrollView(this);sc.addView(result);wrap.addView(sc,LinearLayout.LayoutParams(-1,dp(360)))
        fun show(q:String){
            val matches=snapshot.entries.filter{normalized(it.text).contains(normalized(q))}
            result.text="Найдено: "+matches.size+"\n\n"+matches.take(100).joinToString("\n\n"){it.text}
        }
        input.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){show(s?.toString().orEmpty())}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        show("")
        AlertDialog.Builder(this).setTitle("Каталог магазина").setView(wrap).setPositiveButton("Закрыть",null).show()
    }
    private fun discountReport(){
        val snapshot=LocalCatalog(this).read()?:run{syncCatalog();return}
        if(System.currentTimeMillis()-snapshot.updated !in 0L..(6*60*60*1000L)){
            toast("Каталог устарел. Сначала обнови цены.");syncCatalog();return
        }
        val progress=AlertDialog.Builder(this).setTitle("Сравнение цен").setMessage("Сопоставление с историей…").setNegativeButton("Закрыть",null).create()
        progress.show()
        Thread{
            val report=try{PriceHistory(this).use{it.report(snapshot)}}catch(_:Exception){"Не удалось прочитать историю цен."}
            runOnUiThread{
                if(isFinishing||isDestroyed||!progress.isShowing)return@runOnUiThread
                progress.dismiss()
                AlertDialog.Builder(this).setTitle("Реальная выгода").setMessage(report).setPositiveButton("Закрыть",null).show()
            }
        }.start()
    }
    private fun storeScreen() {
        val updates=card(body);updates.addView(label("Версия приложения: "+updater.installedVersion(),17f,ink,true))
        addButton(updates,"Проверить обновления"){updater.check(true)}
        addButton(updates,"Установить скачанное обновление"){updater.installDownloaded()}
        val catalog=card(body);catalog.addView(label("Каталог и цены",22f,green,true));catalog.addView(label(catalogLabel(),14f,gray))
        addButton(catalog,"Загрузить / обновить весь каталог",true){syncCatalog()}
        addButton(catalog,"Поиск в загруженных товарах"){catalogBrowser()}
        addButton(catalog,"Реальная выгода и история цен"){discountReport()}
        val c=card(body); c.addView(label("Пятёрочка",23f,green,true)); c.addView(label("Встроенный браузер открывает официальный сайт. Войди через X5ID, выбери адрес доставки. Сессия хранится в WebView на телефоне.",16f)); addButton(c,"Открыть магазин в приложении",true){openStore()}; addButton(c,"Скопировать список покупок"){copy(listText())}; c.addView(label("Для конкретного ингредиента нажми «В магазин», выбери товар и перенеси цену и фасовку. Добавление и проверка корзины выполняются на сайте. Режим экспериментальный.",14f,gray))
        val help=card(body); help.addView(label("Передача из ChatGPT",19f,ink,true)); help.addView(label("Поделись обычным списком ингредиентов из ChatGPT или вставь его на главной. Например: Молоко — 500 мл.",15f)); addButton(help,"Скопировать шаблон запроса"){copy("Составь список ингредиентов с количеством в граммах, миллилитрах или штуках, по одному продукту на строку.")}
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
            if(a==null||a !in 1..10_000_000||b==null||b !in 0..10_000_000||c==null||c !in 0..10_000_000||(!v.text.isBlank()&&price==null))toast("Проверь количество и цену") else {items=items.toMutableList().also{it[index]=i.copy(amount=a,atHome=b,pack=c,price=price,checked=false,storeUrl="",storeName="")};update();dlg.dismiss()}
        }};dlg.show()
    }
    private fun importDialog(initial:String) {
        val e=EditText(this).apply{setText(initial);hint="Молоко — 500 мл\nЯйца 6 шт\nМука 1 кг";minLines=5;maxLines=10;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val wrap=vertical().apply{setPadding(dp(16),dp(6),dp(16),dp(6));addView(label("Вставь обычный список с количеством и единицей. Специальный формат не нужен.",14f,gray));addView(e)}
        val dialog=AlertDialog.Builder(this).setTitle("Список покупок").setView(wrap).setNegativeButton("Отмена",null).setPositiveButton("Распознать",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                val parsed=importIngredients(e.text.toString())
                if(parsed.ingredients.isEmpty()){toast("Не найдены строки с количеством");return@setOnClickListener}
                val next=merge(items,parsed.ingredients,1,1)
                val list=parsed.ingredients.joinToString("\n"){"${it.name} · ${it.amount} ${it.unit}"}
                val ignored=if(parsed.ignored.isEmpty())"" else "\n\nНе распознано (${parsed.ignored.size} строк):\n"+parsed.ignored.take(12).joinToString("\n")
                AlertDialog.Builder(this).setTitle("Проверь список").setMessage(list+ignored).setNegativeButton("Назад",null).setPositiveButton("Добавить"){_,_->items=next;tab=0;update();dialog.dismiss()}.show()
            }catch(ex:Exception){toast(ex.message?:"Не удалось распознать")}
        }};dialog.show()
    }
    private fun listText()=items.filter{it.need>0 && !it.checked}.joinToString("\n"){"${it.name} — ${it.need} ${it.unit}"}.ifEmpty{"Список пуст"}
    private fun share(){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,listText()),"Поделиться покупками"))}
    private fun copy(s:String){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Покупки",s));toast("Скопировано")}
    private fun prepareTransfer(){
        val ready=buildTransfer(items,budget,delivery)
        if(ready.error!=null){AlertDialog.Builder(this).setTitle("Сначала проверь список").setMessage(ready.error).setPositiveButton("ОК",null).show();return}
        val rows=JSONArray()
        for(line in ready.lines) rows.put(JSONObject().put("name",line.name).put("url",line.url).put("quantity",line.quantity).put("price",line.price))
        val text=ready.lines.joinToString("\n"){it.name+" × "+it.quantity+" = "+rub(it.quantity*it.price)}
        AlertDialog.Builder(this).setTitle("Перенос в магазин").setMessage(text+"\n\nРасчёт с доставкой: "+rub(ready.total)+"\nОткроется официальный сайт. Проверь адрес и отсутствие лишних товаров. Перенос экспериментальный: при любой неопределённости остановится. Оплаты и удаления товаров нет.").setNegativeButton("Отмена",null).setPositiveButton("Продолжить"){_,_->
            startActivity(Intent(this,CartTransferActivity::class.java).putExtra("lines",rows.toString()))
        }.show()
    }
    private fun batchQuote(){
        val arr=JSONArray()
        for(i in items.filter{it.need>0&&!it.checked})arr.put(JSONObject().put("key",key(i.name,i.unit)).put("name",i.name).put("unit",i.unit).put("amount",i.need))
        if(arr.length()>100){toast("Максимум 100 позиций за один подбор");return}
        val other=items.filter{it.checked}.sumOf{it.cost?:0L}
        startActivityForResult(Intent(this,BatchQuoteActivity::class.java).putExtra("needs",arr.toString()).putExtra("budget",budget).putExtra("delivery",delivery).putExtra("other",other),703)
    }
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
        if(requestCode==704){render();return}
        if(requestCode==703){
            if(resultCode!=RESULT_OK)return
            try{
                val a=JSONArray(data?.getStringExtra("quotes")?:"[]")
                val offers=(0 until a.length()).map{a.getJSONObject(it)}.associateBy{it.getString("key")}
                items=items.map{i->
                    val o=offers[key(i.name,i.unit)]
                    val q=o?.optLong("pack",0)?:0L
                    val p=o?.optLong("price",-1)?:-1L
                    if(o==null)i else if(q in 1..10_000_000&&p in 0..100_000_000&&storeProductUrl(Uri.parse(o.optString("url"))))i.copy(pack=q,price=p,storeUrl=o.optString("url"),storeName=o.optString("title"))else i.copy(price=null,storeUrl="",storeName="")
                }
                tab=0;update();toast("Цены перенесены. Сверь итог магазина.")
            }catch(_:Exception){toast("Не удалось прочитать результаты")}
            return
        }
        if(requestCode==702){
            if(resultCode==RESULT_OK){promptDraft=data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty();tab=3;render()}
            return
        }
        if(requestCode!=701)return
        if(resultCode!=RESULT_OK||data==null){guided=false;return}
        val k=data.getStringExtra("key")?:return
        val pack=data.getLongExtra("pack",0)
        val price=data.getLongExtra("price",-1)
        if(pack !in 1..10_000_000||price !in 0..100_000_000)return
        val index=items.indexOfFirst{key(it.name,it.unit)==k}
        if(index<0)return
        items=items.toMutableList().also{it[index]=it[index].copy(pack=pack,price=price,storeUrl="",storeName="")}
        tab=0;update();toast("Цена и фасовка перенесены. Проверь корзину магазина.")
        if(guided) {
            reviewed.add(k)
            if(summarize(items,budget,delivery).over){guided=false;toast("Сбор остановлен: бюджет превышен.")}
            else nextGuided()
        }
    }
    override fun onResume(){super.onResume();updater.resumeInstall()}
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
}
