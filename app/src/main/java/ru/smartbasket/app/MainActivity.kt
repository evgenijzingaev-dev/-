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
    private val green = Color.rgb(25,104,72)
    private val ink = Color.rgb(27,40,34)
    private val gray = Color.rgb(88,105,96)
    private val bg = Color.rgb(246,247,244)
    private val soft = Color.rgb(231,239,232)
    private val prefs by lazy { getSharedPreferences("basket", MODE_PRIVATE) }
    private var items = listOf<BasketItem>()
    private var budget = 300000L
    private var delivery = 0L
    private var portions = 3
    private val updater by lazy { AppUpdater(this) }
    private val nutritionCache by lazy { NutritionCache(this) }
    private var tab = 3
    private var days = 3
    private var calorieTarget:Int? = null
    private var promptDraft = ""
    private val memory by lazy{AssistantMemory(this)}
    private val family by lazy{FamilyProfiles(this)}
    private val dishStore by lazy{GeneratedDishStore(this)}
    private var draftDishes=emptyList<GeneratedDish>()
    private val assistantHistory=mutableListOf<AssistantMessage>()
    private fun rememberMessage(message:AssistantMessage){
        assistantHistory.add(message)
        while(assistantHistory.size>24)assistantHistory.removeAt(0)
        memory.saveHistory(assistantHistory)
    }
    private var assistantBusy=false
    private val chatConnection by lazy { ChatGptConnection(this) }
    private fun openChatGptSettings() { startActivityForResult(Intent(this, ChatGptSettingsActivity::class.java), 706) }
    private fun compactBudget(): String {
        val whole = (budget / 100).toString().reversed().chunked(3).joinToString("\u202F").reversed()
        val cents = budget % 100
        return whole + (if (cents == 0L) "" else "," + cents.toString().padStart(2, '0')) + "\u00A0₽"
    }
    private var currentPlan: MealPlan? = null
    private var guided = false
    private val reviewed = mutableSetOf<String>()
    private lateinit var body: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        promptDraft = savedInstanceState?.getString("assistant_prompt").orEmpty()
        load(); assistantHistory.addAll(memory.history()); render(); acceptIntent(intent)
        updater.check(false)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("assistant_prompt", promptDraft.take(4000))
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
        days=prefs.getInt("days",3).coerceIn(1,14)
        calorieTarget=prefs.getInt("calorie_target",0).takeIf{it in 100..6000}
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
        prefs.edit().putLong("budget",budget).putLong("delivery",delivery).putInt("portions",portions).putInt("days",days).putInt("calorie_target",calorieTarget?:0).putString("items",a.toString()).apply()
    }
    private fun update() { save(); render() }
    private fun rounded(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun vertical() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun label(s: String, size: Float=16f, color: Int=ink, bold: Boolean=false) = TextView(this).apply { text=s; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun gap(parent: LinearLayout, height: Int=10) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun card(parent: LinearLayout): LinearLayout = vertical().apply { background=rounded(Color.WHITE); setPadding(dp(16),dp(14),dp(16),dp(14)); parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) }) }
    private fun button(s: String, primary: Boolean=false, action: ()->Unit) = Button(this).apply {
        text=s; textSize=17f; isAllCaps=false; letterSpacing=0f
        setTypeface(null,Typeface.BOLD); setTextColor(if(primary) Color.WHITE else green)
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x18000000),rounded(if(primary)green else soft,14),null)
        stateListAnimator=null; minHeight=dp(if(primary)56 else 52); minimumHeight=dp(if(primary)56 else 52); minWidth=0;minimumWidth=0
        setPadding(dp(14),dp(10),dp(14),dp(10)); setOnClickListener { action() }
    }
    private fun addButton(p: LinearLayout, s:String, primary:Boolean=false, action:()->Unit) {
        p.addView(button(s,primary,action),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
    }
    private fun link(s:String,action:()->Unit)=TextView(this).apply{
        text=s;textSize=16f;setTextColor(green);setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL
        minHeight=dp(52);setPadding(dp(10),dp(8),dp(10),dp(8))
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x16000000),null,rounded(Color.WHITE,12))
        isClickable=true;isFocusable=true;setOnClickListener{action()}
    }
    private fun menu(anchor:View,vararg actions:Pair<String,()->Unit>){
        val popup=PopupMenu(this,anchor)
        actions.forEachIndexed{i,p->popup.menu.add(0,i,i,p.first)}
        popup.setOnMenuItemClickListener{item->actions.getOrNull(item.itemId)?.second?.invoke();true}
        popup.show()
    }
    private fun more(p:LinearLayout,description:String,vararg actions:Pair<String,()->Unit>){
        val v=link("⋯"){};v.contentDescription=description;v.gravity=Gravity.CENTER;v.textSize=24f
        v.setOnClickListener{menu(v,*actions)}
        p.addView(v,LinearLayout.LayoutParams(dp(52),-2))
    }
    private fun divider(p:LinearLayout){p.addView(View(this).apply{setBackgroundColor(Color.rgb(232,235,230))},LinearLayout.LayoutParams(-1,dp(1)))}
    private fun render() {
        window.statusBarColor=bg;window.navigationBarColor=Color.WHITE
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=vertical().apply { setBackgroundColor(bg) }
        val top=horizontal().apply{setPadding(dp(20),dp(6),dp(12),dp(6))}
        top.addView(label(when(tab){0->"Корзина";1->"Рецепты";2->"Магазин";else->"Умная корзина"},26f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        val settingsButton=link("⚙"){appSettings()}
        settingsButton.contentDescription="Настройки приложения"
        settingsButton.textSize=26f;settingsButton.gravity=Gravity.CENTER
        top.addView(settingsButton,LinearLayout.LayoutParams(dp(56),dp(56)))
        root.addView(top)
        val sc=ScrollView(this).apply { isFillViewport=true;isVerticalScrollBarEnabled=false;clipToPadding=false;setPadding(dp(16),dp(4),dp(16),dp(12)) }
        body=vertical(); sc.addView(body); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        divider(root)
        val tabs=horizontal().apply{setBackgroundColor(Color.WHITE);setPadding(dp(6),dp(4),dp(6),dp(4))}
        val names=listOf(3 to "Главная",1 to "Рецепты",0 to "Корзина",2 to "Магазин")
        val symbols=listOf("⌂","≡","▣","▤")
        names.forEachIndexed { n,(i,title)->
            val v=vertical().apply{
                gravity=Gravity.CENTER;isClickable=true;isFocusable=true;isSelected=tab==i;contentDescription=title
                setOnClickListener{tab=i;render()}
            }
            val icon=label(symbols[n],22f,if(tab==i)green else gray,true).apply{
                gravity=Gravity.CENTER;background=rounded(if(tab==i)soft else Color.TRANSPARENT,18)
            }
            v.addView(icon,LinearLayout.LayoutParams(dp(58),dp(30)))
            v.addView(label(title,14f,if(tab==i)green else gray,tab==i).apply{gravity=Gravity.CENTER;maxLines=2})
            v.minimumHeight=dp(68)
            tabs.addView(v,LinearLayout.LayoutParams(0,-1,1f))
        }
        root.addView(tabs)
        setContentView(root)
        when(tab){ 0->basketScreen(); 1->recipeScreen(); 2->storeScreen(); else->homeScreen() }
    }
    private fun homeScreen() {
        val hero=card(body)
        hero.addView(label("ChatGPT · умная корзина",24f,ink,true))
        hero.addView(label("Меню, покупки и питание. Запрос и нужные для ответа данные корзины и каталога передаются OpenAI.",14f,gray))
        assistantHistory.takeLast(12).forEach{m->hero.addView(label((if(m.role=="user")"Ты: " else "ChatGPT: ")+m.text,16f,ink))}
        if(draftDishes.isNotEmpty()){
            hero.addView(link("Созданные блюда · "+draftDishes.size+"  ›"){tab=1;render()})
        }
        val input=EditText(this).apply {
            setText(promptDraft);textSize=18f;hint="Например: что приготовить, чем заменить продукт, как сэкономить?"
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
        val shortcuts=horizontal()
        shortcuts.addView(link("Голосовой ввод"){voice()},LinearLayout.LayoutParams(0,-2,1f))
        shortcuts.addView(link("Вставить список"){importDialog("")},LinearLayout.LayoutParams(0,-2,1f))
        hero.addView(shortcuts)
        val params=horizontal()
        val details=label("$days дн.  ·  $portions порц.  ·  "+compactBudget()+(calorieTarget?.let{" · $it ккал" }?:""),16f,gray,true)
        params.addView(details,LinearLayout.LayoutParams(0,-2,1f))
        params.addView(link("Изменить"){planSettings()})
        hero.addView(params)
        hero.addView(link("Моя семья · "+family.read().size+" чел.  ›"){if(!assistantBusy)familyDialog()})
        addButton(hero,"Рекомендации для семьи на сегодня"){
            if(!assistantBusy){
                if(promptDraft.isNotBlank()) toast("Сначала отправь или очисти текущий запрос")
                else if(!family.sharing() || family.read().isEmpty()) familyDialog()
                else { promptDraft="Предложи блюда и продукты на сегодня для всей семьи. Учти возраст, активность, вкусы, ограничения, бюджет и домашние запасы. Объясни выбор и порции."; if(chatConnection.connected())askAssistant() else openChatGptSettings() }
            }
        }
        val connected = chatConnection.connected()
        addButton(hero, if (assistantBusy) "ChatGPT думает…" else if (connected) "Спросить ChatGPT" else "Подключить ChatGPT", true) {
            if (!assistantBusy) { if (chatConnection.connected()) askAssistant() else openChatGptSettings() }
        }
        val tools = horizontal()
        if (assistantHistory.isNotEmpty() || draftDishes.isNotEmpty()) {
            tools.addView(link("Новый диалог") { if (!assistantBusy) { assistantHistory.clear(); memory.clearHistory(); draftDishes = emptyList(); render() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        tools.addView(link("Настроить предпочтения") { if (!assistantBusy) memoryDialog() }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(tools)
        addButton(hero,"Локальный подбор (без ChatGPT)") {
            try {
                val parsed=parseRequest(promptDraft,days,portions.coerceAtMost(20),budget)
                val req=if(parsed.kcalTarget==null)parsed.copy(kcalTarget=calorieTarget)else parsed
                planFromCatalog(req)
            }catch(e:Exception){toast(e.message?:"Проверь запрос")}
        }
        hero.addView(label("Меню из доступных продуктов с учётом бюджета и КБЖУ. Цель калорийности необязательна.",14f,gray))
        val shop=card(body)
        shop.addView(label("Пятёрочка",19f,ink,true))
        shop.addView(label(catalogLabel(),15f,gray))
        val shopActions=horizontal()
        shopActions.addView(link("Обновить цены"){syncCatalog()},LinearLayout.LayoutParams(0,-2,1f))
        shopActions.addView(link("Выгодные товары"){discountReport()},LinearLayout.LayoutParams(0,-2,1f))
        shop.addView(shopActions)
        if(items.isNotEmpty()) {
            val c=card(body);c.addView(label("Текущий список · ${items.size} поз.",18f,ink,true))
            val s=summarize(items,budget,delivery)
            c.addView(label(if(s.complete)rub(s.totalKnown) else "Цены ещё не проверены",15f,gray))
            c.addView(link("Открыть корзину  ›"){tab=0;render()})
        }
    }
    private fun askAssistant(){
        if(assistantBusy)return
        val prompt=promptDraft.trim()
        if(prompt.isEmpty()||prompt.length>4000){toast("Введи запрос до 4000 символов");return}
        val connection=ChatGptConnection(this)
        if(!connection.connected()){
            AlertDialog.Builder(this).setTitle("Подключи ChatGPT").setMessage("Для диалога нужен активный вход и разрешение использования плана.")
                .setNegativeButton("Отмена",null).setPositiveButton("Подключить"){_,_->openChatGptSettings()}.show()
            return
        }
        val history=assistantHistory.toList()
        val preferences=memory.preferences();val targetDays=days;val people=portions;val target=calorieTarget
        val previous=items.toList();val limit=budget;val shipping=delivery
        val familyContext=family.sharedContext()
        assistantBusy=true;render()
        Thread{
            try{
                val engine=BasketAssistant(connection,LocalCatalog(this).read(),previous,limit,shipping,nutritionCache::get,preferences,targetDays,people,target,familyContext)
                val answer=engine.ask(history,prompt)
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed){
                        rememberMessage(AssistantMessage("user",prompt));rememberMessage(AssistantMessage("assistant",answer.text))
                        if(answer.dishes.isNotEmpty())draftDishes=answer.dishes
                        if (promptDraft.trim() == prompt) promptDraft = ""
                        assistantBusy=false;render()
                        val showBasket={answer.proposal?.let{showAssistantProposal(it,previous,limit,shipping)};Unit}
                        if(answer.preferenceProposal!=null&&answer.preferenceProposal!=preferences){
                            val proposed=answer.preferenceProposal
                            AlertDialog.Builder(this).setTitle("Обновить память предпочтений?")
                                .setMessage(if(proposed.isBlank())"Очистить сохранённые предпочтения?" else proposed)
                                .setNegativeButton("Нет"){_,_->rememberMessage(AssistantMessage("assistant","Приложение: изменение памяти отклонено."))}
                                .setPositiveButton("Сохранить"){_,_->if(memory.preferences()==preferences){memory.savePreferences(proposed);rememberMessage(AssistantMessage("assistant","Приложение: предпочтения сохранены."))}}
                                .create().apply{setOnDismissListener{render();showBasket()};show()}
                        }else showBasket()
                    }
                }
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed){
                assistantBusy=false
                render()
                AlertDialog.Builder(this).setTitle("Ответ ChatGPT не получен")
                    .setMessage(chatGptFailureMessage(e) ?: "Проверь подключение и лимиты ChatGPT.")
                    .setNegativeButton("Закрыть", null)
                    .apply {
                        if (promptDraft.trim() == prompt && connection.connected() &&
                            generateSequence<Throwable>(e) { it.cause }.any { it is java.net.UnknownHostException }) {
                            setPositiveButton("Повторить") { _, _ -> askAssistant() }
                        }
                    }.show()
            }}}
        }.start()
    }
    internal fun showAssistantProposal(proposal:BasketProposal,previous:List<BasketItem>,limit:Long,shipping:Long){
        if(items!=previous||budget!=limit||delivery!=shipping){toast("Корзина или бюджет изменились. Повтори запрос.");return}
        val proposed=proposal.items
        val s=summarize(proposed,limit,shipping)
        val summary=if(s.complete)"Итого с доставкой: "+rub(s.totalKnown) else "Известный минимум с доставкой: "+rub(s.totalKnown)+" · без цены: "+s.unknown
        val catalogHint = proposal.catalogHint?.let { "\n$it" }.orEmpty()
        val rows = proposal.previewRows(nutritionCache::get)
        val warning=if(s.over)"\nПревышен текущий бюджет." else if(!s.complete)"\nБюджет не подтверждён." else ""
        AlertDialog.Builder(this).setTitle("Предложение ChatGPT")
            .setMessage(summary+warning+"\nБюджет: "+rub(limit)+"\nЦены предварительные."+catalogHint+"\n\n"+rows+"\n\nЗаменить текущий список? Заказ не оформляется.")
            .setNegativeButton("Отклонить"){_,_->rememberMessage(AssistantMessage("assistant","Приложение: пользователь отклонил предложение, корзина не изменена."));render()}
            .setPositiveButton("Применить"){_,_->
                if(items!=previous||budget!=limit||delivery!=shipping){toast("Условия изменились. Повтори запрос.")}
                else if(proposal.catalogUpdated?.let{System.currentTimeMillis()-it !in 0L..21_600_000L}==true){toast("Цены устарели. Повтори запрос.")}
                else{items=proposed;currentPlan=null;rememberMessage(AssistantMessage("assistant","Приложение: пользователь подтвердил замену корзины."));tab=0;update()}
            }.show()
    }
    private fun familyDialog(){
        val panel=vertical().apply{setPadding(dp(20),dp(8),dp(20),dp(12))}
        val members=family.read()
        panel.addView(label("Профили хранятся на устройстве. При включении передачи возраст, рост, вес, пол, активность, цели, ограничения и записи дневника без имени передаются OpenAI при запросах. Указывай только данные, которыми вправе делиться.",14f,gray))
        val share=CheckBox(this).apply{ text="Учитывать семейные данные в ChatGPT";isChecked=family.sharing()
            setOnCheckedChangeListener{_,checked->family.setSharing(checked)} }
        panel.addView(share)
        panel.addView(CheckBox(this).apply{
            text="Автоматически запрашивать рекомендации при открытии (раз в день, расходует лимит ChatGPT)"
            isChecked=prefs.getBoolean("family_auto",false)
            setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("family_auto",checked).apply()}
        })
        val d=AlertDialog.Builder(this).setTitle("Моя семья").setView(ScrollView(this).apply{addView(panel)}).setPositiveButton("Закрыть",null).create()
        members.forEachIndexed{i,m->
            panel.addView(link(m.name+" · "+m.age+" лет  ›"){d.dismiss();editFamily(i)})
            m.maintenance()?.let{panel.addView(label("Ориентир поддержания веса: "+it+" ккал/сутки",14f,gray))}
            m.macros()?.let{panel.addView(label("Пример БЖУ: "+it.protein+" / "+it.fat+" / "+it.carbs+" г в сутки",14f,gray))}
            panel.addView(link("Дневник питания"){d.dismiss();foodLogDialog(i)})
        }
        addButton(panel,"Импорт моих данных Health Connect"){d.dismiss();startActivity(Intent(this,FamilyHealthActivity::class.java))}
        if(members.size<12)addButton(panel,"Добавить члена семьи"){d.dismiss();editFamily(null)}
        d.show()
    }
    private fun foodLogDialog(index:Int){
        val member=family.read().getOrNull(index)?:return
        val panel=vertical().apply{setPadding(dp(20),dp(8),dp(20),dp(12))}
        panel.addView(label("Последние записи. При включённой передаче профиля учитываются ChatGPT.",14f,gray))
        family.log(index).forEach{panel.addView(label(it,14f,ink))}
        val food=field(hint="Блюдо или продукт")
        panel.addView(food)
        val rating=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Съедено","Понравилось","Не понравилось"))}
        panel.addView(rating)
        val d=AlertDialog.Builder(this).setTitle("Дневник · "+member.name).setView(ScrollView(this).apply{addView(panel)})
            .setNegativeButton("Закрыть",null).setNeutralButton("Очистить"){_,_->family.clearLog(index);foodLogDialog(index)}
            .setPositiveButton("Добавить",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val value=food.text.toString().trim()
            if(value.isBlank()||value.length>200)toast("Введи блюдо до 200 символов")
            else{family.record(index,value,rating.selectedItem.toString());d.dismiss();foodLogDialog(index)}
        }}
        d.show()
    }
    private fun editFamily(index:Int?){
        val members=family.read();val m=index?.let{members.getOrNull(it)}
        val n=field(m?.name.orEmpty(),"Например, взрослый 1")
        val age=field(m?.age?.toString().orEmpty(),numeric=true)
        val h=field(m?.height?.toString().orEmpty(),numeric=true)
        val w=field(m?.weight?.toString().orEmpty(),numeric=true,decimal=true)
        val a=field(m?.activity.orEmpty(),"Шаги, тренировки, работа")
        val sex=field(m?.sex.orEmpty(),"Мужской / Женский")
        val level=field(m?.activityLevel.orEmpty(),"Низкая / Лёгкая / Умеренная / Высокая")
        val g=field(m?.goal.orEmpty(),"Поддержание веса, набор массы")
        val t=field(m?.tastes.orEmpty(),"Любимые и нелюбимые блюда")
        val r=field(m?.restrictions.orEmpty(),"Аллергии и ограничения")
        val form=fields("Имя или псевдоним" to n,"Возраст (лет)" to age,"Рост (см, необязательно)" to h,"Вес (кг, необязательно)" to w,"Активность" to a,"Пол для расчёта (необязательно)" to sex,"Уровень активности (необязательно)" to level,"Цель (только для взрослых)" to g,"Предпочтения" to t,"Ограничения" to r)
        val d=AlertDialog.Builder(this).setTitle("Профиль семьи").setView(ScrollView(this).apply{addView(form)}).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null)
        if(index!=null)d.setNeutralButton("Удалить"){_,_->family.save(members.filterIndexed{i,_->i!=index});render();familyDialog()}
        val dlg=d.create()
        dlg.setOnShowListener{dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val years=age.text.toString().toIntOrNull()
            val hs=h.text.toString();val ws=w.text.toString()
            val height=hs.toIntOrNull();val weight=ws.replace(',','.').toDoubleOrNull()
            if(years==null || (hs.isNotBlank()&&height==null) || (ws.isNotBlank()&&weight==null)){toast("Проверь числовые поля");return@setOnClickListener}
            val member=FamilyMember(n.text.toString().trim(),years,height,weight,a.text.toString(),if(years<18)"" else g.text.toString(),t.text.toString(),r.text.toString(),m?.id?:java.util.UUID.randomUUID().toString(),sex.text.toString().trim(),level.text.toString().trim())
            if(!member.valid()){toast("Проверь данные профиля");return@setOnClickListener}
            val updated=members.toMutableList();if(index==null)updated.add(member)else updated[index]=member
            family.save(updated);dlg.dismiss();render();familyDialog()
        }}
        dlg.show()
    }
    private fun memoryDialog(){
        val panel=vertical().apply{setPadding(dp(20),dp(8),dp(20),dp(12))}
        panel.addView(label("Предпочтения, ограничения, любимые продукты. Хранятся на устройстве и передаются OpenAI при запросе. Не вводи секреты.",14f,gray))
        val edit=EditText(this).apply{setText(memory.preferences());minLines=4;maxLines=8;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;filters=arrayOf(android.text.InputFilter.LengthFilter(4000))}
        panel.addView(edit)
        AlertDialog.Builder(this).setTitle("Память SmartBasket").setView(panel).setNegativeButton("Отмена",null)
            .setNeutralButton("Очистить всё"){_,_->AlertDialog.Builder(this).setTitle("Удалить локальную память и историю?")
                .setMessage("Корзина и каталог сохранятся. Это не удаляет данные из аккаунта ChatGPT.")
                .setNegativeButton("Нет",null).setPositiveButton("Удалить"){_,_->memory.clearAll();assistantHistory.clear();render()}.show()}
            .setPositiveButton("Сохранить"){_,_->memory.savePreferences(edit.text.toString());render()}.show()
    }
    private fun appSettings(){
        val panel=vertical().apply{setPadding(dp(20),dp(8),dp(20),dp(12))}
        panel.addView(label("Версия приложения "+updater.installedVersion(),17f,gray))
        val dlg=AlertDialog.Builder(this).setTitle("Настройки").setView(panel).setPositiveButton("Закрыть",null).create()
        addButton(panel,"Проверить обновление",true){dlg.dismiss();updater.check(true)}
        addButton(panel,"Установить скачанное обновление"){dlg.dismiss();updater.installDownloaded()}
        addButton(panel,"Бюджет и доставка"){dlg.dismiss();budgetDialog()}
        addButton(panel,"Память и предпочтения"){dlg.dismiss();if(!assistantBusy)memoryDialog()else toast("Дождись ответа ChatGPT")}
        addButton(panel,"Подключение ChatGPT"){dlg.dismiss();if(!assistantBusy)openChatGptSettings()else toast("Дождись ответа ChatGPT")}
        dlg.show()
    }
    private fun planSettings(){
        val kcal=field(calorieTarget?.toString().orEmpty(),hint="Необязательно",numeric=true)
        val d=field(days.toString(),numeric=true)
        val p=field(portions.toString(),numeric=true)
        val b=field("${budget/100}.${(budget%100).toString().padStart(2,'0')}",numeric=true,decimal=true)
        dialog("Параметры меню",fields("Дней (1–14)" to d,"Порций (1–20)" to p,"Бюджет, ₽" to b,"Ккал на человека за день" to kcal)){
            val n=d.text.toString().toIntOrNull();val count=p.text.toString().toIntOrNull();val limit=money(b.text.toString())
            val target=kcal.text.toString().trim().toIntOrNull()
            if(n==null||n !in 1..14||count==null||count !in 1..20||limit==null||(!kcal.text.isBlank()&&(target==null||target !in 100..6000)))false
            else{days=n;portions=count;budget=limit;calorieTarget=target;update();true}
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
    private fun planFromCatalog(req:PlanRequest){
        val progress=AlertDialog.Builder(this).setTitle("Подбираем меню").setMessage("Сравниваем ассортимент, стоимость упаковок и КБЖУ…").setNegativeButton("Отмена",null).create()
        val previous=items
        val deliveryCost=delivery
        progress.show()
        Thread{
            try{
                val snapshot=LocalCatalog(this).read()
                val fresh=snapshot!=null&&System.currentTimeMillis()-snapshot.updated in 0L..(6*60*60*1000L)
                val cached=mutableMapOf<String,ProductNutrition?>()
                val result=if(fresh&&snapshot!=null)buildCatalogPlan(req,snapshot.entries,deliveryCost,previous){url->
                    cached.getOrPut(url){nutritionCache.get(url)}
                }else null
                runOnUiThread{
                    if(isFinishing||isDestroyed||!progress.isShowing)return@runOnUiThread
                    progress.dismiss()
                    if(items!=previous||delivery!=deliveryCost){toast("Условия изменились. Повтори подбор.");return@runOnUiThread}
                    if(result!=null)previewPlan(result.plan,result,req)
                    else AlertDialog.Builder(this).setTitle(if(fresh)"Не найден вариант" else "Нужны свежие цены")
                        .setMessage(if(fresh)"Не удалось найти меню в рамках бюджета, доступного ассортимента и ограничений. Попробуй изменить параметры." else "Обнови каталог, чтобы подобрать реальные товары и цены.")
                        .setNegativeButton("Назад",null).setPositiveButton(if(fresh)"Изменить параметры" else "Обновить каталог"){_,_->if(fresh)planSettings()else syncCatalog()}
                        .setNeutralButton("Меню без цен"){_,_->previewPlan(buildPlan(req),null,req)}.show()
                }
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed&&progress.isShowing){progress.dismiss();toast("Не удалось подобрать меню")}}}
        }.start()
    }
    private fun nutritionDialog(){
        val report=basketNutrition(items,nutritionCache::get)
        val rows=report.lines.joinToString("\n"){line->line.name+": "+(line.total?.label()?.let{if(line.estimated)"≈ "+it else it} ?: "нет данных")}
        val mealText=currentPlan?.let{plan->
            "\n\nМеню · на одну порцию:\n"+plan.meals.groupBy{it.day}.entries.joinToString("\n\n"){(day,meals)->
                val reports=meals.map{it to mealNutrition(it,items,nutritionCache::get)}
                val total=NutritionReport(reports.flatMap{it.second.lines})
                "День "+day+" · "+total.summary()+"\n"+reports.joinToString("\n"){(meal,r)->mealTitle(meal)+": "+(if(r.missing>0)"неполные данные" else if(r.estimated>0)"≈ "+r.total.label() else r.total.label())}
            }
        }.orEmpty()
        val urls=items.map{it.storeUrl}.filter{storeProductUrl(Uri.parse(it))}.distinct()
        val pending=urls.filter{nutritionCache.get(it)==null}
        val d=AlertDialog.Builder(this).setTitle("Калории и БЖУ").setMessage("На количество ингредиентов в списке, а не на купленные упаковки.\n"+report.summary()+"\n\n"+rows+mealText+"\n\n≈ — приблизительная оценка; приготовление и отходы не учтены.")
            .setPositiveButton("Закрыть",null)
        if(pending.isNotEmpty())d.setNeutralButton("Уточнить КБЖУ (${pending.size})"){_,_->startActivityForResult(Intent(this,NutritionActivity::class.java).putStringArrayListExtra("urls",ArrayList(pending)),705)}
        d.show()
    }
    private fun previewPlan(plan:MealPlan,priced:PricedMealPlan?=null,req:PlanRequest?=null) {
        val proposed=priced?.items?:plan.ingredients()
        val lookup=nutritionCache::get
        val menu=plan.meals.groupBy{it.day}.entries.joinToString("\n\n"){(day,meals)->
            val reports=meals.map{it to mealNutrition(it,proposed,lookup)}
            val daily=NutritionReport(reports.flatMap{it.second.lines})
            "День "+day+" · на человека\n"+daily.summary()+"\n"+reports.joinToString("\n"){(meal,n)->
                meal.kind+": "+mealTitle(meal)+"\n"+(if(n.missing>0)"КБЖУ неполные" else (if(n.estimated>0)"≈ " else "")+n.total.label()+" / порция")
            }
        }
        val goal=req?.kcalTarget?.let{calorieGoalSummary(plan,proposed,it,req.kcalCeiling,lookup)}.orEmpty()
        val cost=if(priced==null)"Цены не проверены. Бюджет не подтверждён." else "Покупки с доставкой: "+rub(priced.total)+" · остаток "+rub(plan.budget-priced.total)+"\nЦены из локального каталога; проверь перед заказом."
        val details=cost+"\n"+goal+"\n"+plan.portions+" порц. · бюджет "+rub(plan.budget)+"\n\n"+menu
        val d=AlertDialog.Builder(this).setTitle(if(priced==null)"Предварительное меню" else "Меню по каталогу").setMessage(details)
            .setNegativeButton("Назад",null).setPositiveButton(if(items.isEmpty())"Создать список" else "Заменить список"){_,_->
                val previous=items.associateBy{key(it.name,it.unit)}
                items=if(priced!=null)proposed else proposed.map{p->previous[key(p.name,p.unit)]?.let{p.copy(atHome=it.atHome,pack=it.pack,price=null)}?:p}
                budget=plan.budget;portions=plan.portions;days=plan.meals.maxOf{it.day};currentPlan=plan;tab=0;update()
            }
        if(items.isNotEmpty()&&priced==null)d.setNeutralButton("Добавить к списку"){_,_->
            try{
                items=merge(items,proposed.map{Ingredient(it.name,it.amount,it.unit)},1,1)
                currentPlan=null;budget=plan.budget;portions=plan.portions;tab=0;update()
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
        c.addView(label("Доставка (введено вручную): ${rub(delivery)}",15f,gray))
        if(items.isNotEmpty())c.addView(link("Калории и БЖУ  ›"){nutritionDialog()})
        val hasNeeds=items.any{it.need>0&&!it.checked}
        if(hasNeeds){
            val ready=buildTransfer(items,budget,delivery)
            if(ready.error==null)addButton(c,"Перенести в Пятёрочку",true){prepareTransfer()}
            else addButton(c,"Подобрать товары и цены",true){chooseFromCatalog()}
            c.addView(label(catalogLabel(),14f,gray))
        }
        val tools=horizontal()
        tools.addView(link("＋ Продукт"){addDialog()},LinearLayout.LayoutParams(0,-2,1f))
        more(tools,"Действия с корзиной",
            "Вставить список" to {importDialog("")},
            "Подобрать товары" to {chooseFromCatalog()},
            "Обновить каталог" to {syncCatalog()},
            "Поиск по категориям" to {batchQuote()},
            "Открывать товары по очереди" to {startGuided()},
            "Бюджет и доставка" to {budgetDialog()},
            "Поделиться списком" to {share()},
            "Очистить список" to {AlertDialog.Builder(this).setTitle("Удалить все покупки?").setNegativeButton("Отмена",null).setPositiveButton("Очистить"){_,_->items=emptyList();update()}.show();Unit})
        c.addView(tools)
        currentPlan?.let { plan->c.addView(link("Посмотреть меню  ›"){AlertDialog.Builder(this).setTitle("Меню").setMessage(plan.meals.joinToString("\n"){"День ${it.day} · ${it.kind}: ${mealTitle(it)}"}).setPositiveButton("ОК",null).show()}) }
        if(items.isEmpty()) { val empty=card(body); empty.addView(label("Пока нет покупок",18f,ink,true)); empty.addView(label("Выбери рецепты или добавь свои продукты.",14f,gray)); addButton(empty,"Выбрать рецепты") { tab=1;render() }; return }
        val progress = label("Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}",19f,ink,true)
        body.addView(progress)
        val list=card(body)
        items.forEachIndexed { index,i ->
            if(index>0)divider(list)
            val row=horizontal()
            val check=CheckBox(this).apply {
                contentDescription=i.name;isChecked=i.checked;minWidth=dp(48)
                setOnCheckedChangeListener { _,v ->
                    items=items.toMutableList().also {it[index]=it[index].copy(checked=v)};save()
                    progress.text="Покупки · ${items.count{it.checked || it.need==0L}} / ${items.size}"
                }
            }
            row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
            val details=vertical().apply{setPadding(0,dp(10),0,dp(10))}
            details.addView(label(i.name,18f,ink,true))
            details.addView(label("Купить ${i.need} ${i.unit}"+if(i.atHome>0)" · дома ${i.atHome}" else "",14f,gray))
            if(i.storeName.isNotBlank())details.addView(label(i.storeName,14f,gray))
            val cost=i.cost
            details.addView(label(if(i.need==0L)"Есть дома" else if(cost==null)"Цена неизвестна" else rub(cost),17f,green,true))
            details.setOnClickListener{editDialog(index)}
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            more(row,"Действия: "+i.name,"Цена и количество" to {editDialog(index)},"Открыть магазин" to {openStore(index)})
            list.addView(row)
        }
    }

    private fun recipeScreen() {
        val top=card(body)
        top.addView(label("Рецепты ChatGPT",23f,ink,true))
        top.addView(label("Создавай любые блюда в диалоге. КБЖУ указаны на порцию; оценки не равны данным этикетки.",14f,gray))
        addButton(top,"Придумать блюда",true){tab=3;promptDraft="";render()}
        val saved=dishStore.read()
        if(draftDishes.isNotEmpty()){
            body.addView(label("Новые блюда",19f,ink,true))
            for(d in draftDishes)renderDish(d,false)
        }
        body.addView(label("Сохранённые блюда",19f,ink,true))
        if(saved.isEmpty())body.addView(label("Пока нет сохранённых рецептов.",15f,gray))
        else for(d in saved.asReversed())renderDish(d,true)
    }
    private fun renderDish(d:GeneratedDish,saved:Boolean){
        val c=card(body);val r=d.recipe
        c.addView(label(r.name,20f,ink,true))
        c.addView(label(r.servings.toString()+" порц. · "+r.ingredients.size+" ингредиентов",14f,gray))
        c.addView(label(dishNutritionLabel(d),15f,green))
        addButton(c,"Ингредиенты и приготовление"){
            AlertDialog.Builder(this).setTitle(r.name)
                .setMessage(r.ingredients.joinToString("\n"){it.name+" — "+it.amount+" "+it.unit}+"\n\n"+r.method+"\n\n"+dishNutritionLabel(d))
                .setPositiveButton("Закрыть",null).show()
        }
        if(saved)addButton(c,"Удалить рецепт"){dishStore.write(dishStore.read().filterNot{it.recipe==r});render()}
        else addButton(c,"Сохранить рецепт"){dishStore.write((dishStore.read().filterNot{it.recipe==r}+d).takeLast(60));draftDishes=draftDishes.filterNot{it.recipe==r};render()}
        addButton(c,"Подобрать продукты через ChatGPT"){
            tab=3;promptDraft="Подбери товары для блюда "+r.name+" на "+r.servings+" порций: "+r.ingredients.joinToString(", "){it.name+" "+it.amount+" "+it.unit}+". Проверь цены и предложи добавление к текущей корзине.";render()
        }
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
                val original=items
                val progress=AlertDialog.Builder(this).setTitle("Подбор товаров").setMessage("Сравниваем цены в сохранённом каталоге…").setNegativeButton("Отмена",null).create()
                progress.show()
                Thread{
                    try{
                        var found=0
                        val result=original.map{i->
                            if(i.need<=0||i.checked)i else {
                                val m=findCatalogMatch(snapshot.entries,i)
                                if(m==null)i.copy(price=null,storeName="",storeUrl="")
                                else{found++;i.copy(pack=m.offer.pack,price=m.offer.price,storeName=m.offer.description,storeUrl=m.entry.url)}
                            }
                        }
                        runOnUiThread{
                            if(isFinishing||isDestroyed||!progress.isShowing)return@runOnUiThread
                            progress.dismiss()
                            if(items!=original){toast("Список изменился, повтори подбор");return@runOnUiThread}
                            items=result;tab=0;update();toast("Из каталога подобрано "+found+" позиций")
                        }
                    }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed&&progress.isShowing){progress.dismiss();toast("Ошибка подбора товаров")}}}
                }.start()
            }.show()
    }
    private fun catalogBrowser(){
        val snapshot=LocalCatalog(this).read()?:run{syncCatalog();return}
        val wrap=vertical().apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
        val input=field(hint="Поиск по всему каталогу")
        val count=label("",14f,gray)
        val list=ListView(this).apply{dividerHeight=dp(1)}
        val searchIndex=snapshot.entries.map{entry->normalized(entry.name+" "+entry.text)}
        var matches=snapshot.entries
        val adapter=object:BaseAdapter(){
            override fun getCount():Int=matches.size
            override fun getItem(position:Int):CatalogEntry=matches[position]
            override fun getItemId(position:Int):Long=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=convertView as? LinearLayout ?: vertical().apply{
                    setPadding(dp(2),dp(8),dp(2),dp(8))
                    addView(label("",16f,ink,true))
                    addView(label("",14f,gray))
                }
                val display=catalogDisplay(getItem(position))
                (row.getChildAt(0) as TextView).text=display.title
                (row.getChildAt(1) as TextView).text=display.priceLabel
                return row
            }
        }
        list.adapter=adapter
        wrap.addView(label(catalogLabel(),14f,gray));wrap.addView(input);wrap.addView(count)
        wrap.addView(list,LinearLayout.LayoutParams(-1,minOf(dp(360),(resources.displayMetrics.heightPixels*.42f).toInt())))
        fun show(q:String){
            val term=normalized(q)
            matches=if(term.isEmpty()) snapshot.entries else snapshot.entries.filterIndexed{index,_->searchIndex[index].contains(term)}
            count.text=if(matches.isEmpty()) "Ничего не найдено" else "Найдено: "+matches.size
            adapter.notifyDataSetChanged()
            list.setSelection(0)
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
        val catalog=card(body)
        catalog.addView(label("Пятёрочка",24f,ink,true))
        catalog.addView(label(catalogLabel(),14f,gray))
        addButton(catalog,"Обновить каталог",true){syncCatalog()}
        val actions=horizontal()
        actions.addView(link("Все товары  ›"){catalogBrowser()},LinearLayout.LayoutParams(0,-2,1f))
        more(actions,"Действия магазина","Открыть сайт / изменить адрес" to {openStore()},"Скопировать список покупок" to {copy(listText())})
        catalog.addView(actions)
        val deals=card(body)
        deals.addView(label("Реальная выгода",21f,ink,true))
        deals.addView(label("Сравнение текущих цен с историей, а не только с зачёркнутой ценой.",14f,gray))
        deals.addView(link("Посмотреть предложения  ›"){discountReport()})
        val help=card(body)
        help.addView(label("Импорт списка",18f,ink,true))
        help.addView(link("Вставить список  ›"){importDialog("")})
        help.addView(link("Скопировать шаблон запроса"){copy("Составь список ингредиентов с количеством в граммах, миллилитрах или штуках, по одному продукту на строку.")})
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
        if(requestCode==706){render();return}
        if(requestCode==705){if(resultCode==RESULT_OK)nutritionDialog();return}
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
    override fun onResume(){
        super.onResume();updater.resumeInstall()
        if(tab==3 && !assistantBusy && promptDraft.isBlank() && family.sharing() && family.read().isNotEmpty() &&
            prefs.getBoolean("family_auto",false) && chatConnection.connected()){
            val today=java.time.LocalDate.now().toString()
            if(prefs.getString("family_auto_day","")!=today){
                prefs.edit().putString("family_auto_day",today).apply()
                promptDraft="Предложи сегодня семейное меню и подходящие продукты, учитывая индивидуальные порции, возраст, ограничения, бюджет и запасы. Не меняй корзину без запроса."
                askAssistant()
            }
        }
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
}
