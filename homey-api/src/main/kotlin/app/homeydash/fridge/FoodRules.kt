package app.homeydash.fridge

import app.homeydash.recipes.Text
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Where something is kept. */
enum class Place(val en: String, val sv: String) {
    Fridge("Fridge", "Kylen"),
    Freezer("Freezer", "Frysen"),
    Pantry("Pantry", "Skafferiet"),
}

/**
 * A kind of food and how long it keeps. [days] is unopened in its usual place (from the
 * purchase date, when the pack has no date on it), [openedDays] after opening, and
 * [freezerDays] in the freezer (0 when it doesn't freeze well). [strict] foods have a
 * "use by" (sista förbrukningsdag): past it they go in the bin, not the sniff test.
 */
data class FoodCategory(
    val key: String,
    val en: String,
    val sv: String,
    val emoji: String,
    val days: Int,
    val openedDays: Int,
    val freezerDays: Int,
    val strict: Boolean = false,
    val place: Place = Place.Fridge,
    /** Swedish and English words in product names; the longest match wins. */
    val keywords: List<String> = emptyList(),
    /** Open Food Facts category tags, e.g. en:yogurts. */
    val tags: List<String> = emptyList(),
)

/** How sure the date is and where it came from. */
enum class Basis {
    Printed,
    /** From how long this very product kept before (or what the AI found for it). */
    Product,
    /** From the usual time for its kind. */
    Guessed,
    Opened,
    Frozen,
    Thawed,
}

enum class Urgency { Gone, Today, Soon, Fine }

/** When something should be eaten by, and why. */
data class Estimate(val date: LocalDate, val basis: Basis, val strict: Boolean, val daysLeft: Long) {
    val urgency: Urgency
        get() = when {
            daysLeft < 0 -> Urgency.Gone
            daysLeft == 0L -> Urgency.Today
            daysLeft <= 2 -> Urgency.Soon
            else -> Urgency.Fine
        }

    /** A short label for a pill: "Today", "2 days", "3 weeks", "Expired". */
    fun label(swedish: Boolean = false): String = when {
        daysLeft < 0 && strict -> if (swedish) "Gått ut" else "Expired"
        daysLeft < 0 -> if (swedish) "Bäst före passerat" else "Past best before"
        daysLeft == 0L -> if (swedish) "Idag" else "Today"
        daysLeft == 1L -> if (swedish) "Imorgon" else "Tomorrow"
        daysLeft < 14 -> if (swedish) "$daysLeft dagar" else "$daysLeft days"
        daysLeft < 60 -> (daysLeft / 7).let { if (swedish) "$it veckor" else "$it weeks" }
        else -> (daysLeft / 30).let { if (swedish) "$it mån" else "$it months" }
    }

    /** What to do when it is past its date. */
    fun advice(swedish: Boolean = false): String? = when {
        daysLeft >= 0 -> null
        strict -> if (swedish) "Släng den, det är en sista förbrukningsdag." else "Throw it away; it's a use-by date."
        else -> if (swedish) "Titta, lukta, smaka innan du slänger." else "Look, smell and taste before throwing it away."
    }
}

/** The food categories, shelf lives and the guesswork from product names. See plan/fridge-research.md. */
object FoodRules {
    private const val MONTH = 30

    val all: List<FoodCategory> = listOf(
        FoodCategory(
            "mince", "Mince", "Färs", "🥩", 2, 1, 4 * MONTH, strict = true,
            keywords = listOf("färs", "köttfärs", "nötfärs", "blandfärs", "fläskfärs", "lammfärs", "kycklingfärs", "viltfärs", "hamburgare", "burgare", "mince", "minced", "ground beef", "ground pork", "ground meat"),
            tags = listOf("en:minced-meats", "en:ground-meats", "en:minced-beef", "en:ground-beef", "en:minced-pork", "en:minced-poultry"),
        ),
        FoodCategory(
            "poultry", "Chicken & poultry", "Kyckling & fågel", "🍗", 2, 1, 9 * MONTH, strict = true,
            keywords = listOf("kyckling", "kycklingfilé", "kycklinglår", "lårfilé", "kalkon", "anka", "ankbröst", "fågel", "chicken", "turkey", "duck", "poultry"),
            tags = listOf("en:poultries", "en:chickens", "en:chicken-meats", "en:chicken-breasts", "en:turkeys", "en:ducks", "en:poultry-meats"),
        ),
        FoodCategory(
            "meat", "Meat", "Kött", "🥩", 4, 2, 6 * MONTH, strict = true,
            keywords = listOf("kött", "nötkött", "fläskkött", "biff", "entrecote", "oxfilé", "fläskfilé", "kotlett", "fläskkotlett", "karré", "högrev", "stek", "lammstek", "ryggbiff", "fläsk", "revben", "sidfläsk", "lamm", "vilt", "älg", "beef", "pork", "steak", "lamb", "chops", "ribs", "meat"),
            tags = listOf("en:meats", "en:beef", "en:pork", "en:lamb", "en:veal", "en:fresh-meats", "en:beef-meats", "en:pork-meats"),
        ),
        FoodCategory(
            "fish", "Fish & seafood", "Fisk & skaldjur", "🐟", 1, 1, 3 * MONTH, strict = true,
            keywords = listOf("fisk", "lax", "laxfilé", "torsk", "sej", "kolja", "röding", "abborre", "gös", "räkor", "kräftor", "musslor", "hummer", "krabba", "skaldjur", "fish", "salmon", "cod", "shrimp", "prawns", "mussels", "seafood"),
            tags = listOf("en:fishes", "en:seafood", "en:salmons", "en:fresh-fishes", "en:shrimps", "en:fish-fillets"),
        ),
        FoodCategory(
            "smoked_fish", "Smoked & cured fish", "Rökt & gravad fisk", "🐟", 10, 3, 2 * MONTH, strict = true,
            keywords = listOf("rökt lax", "gravad lax", "gravlax", "kallrökt", "varmrökt", "rökt makrill", "smoked salmon", "smoked fish"),
            tags = listOf("en:smoked-fishes", "en:smoked-salmons", "en:gravlax"),
        ),
        FoodCategory(
            "cold_cuts", "Cold cuts", "Pålägg & chark", "🥓", 7, 4, 2 * MONTH, strict = true,
            keywords = listOf("pålägg", "skinka", "kokt skinka", "rökt skinka", "salami", "bacon", "rostbiff", "kalkonpålägg", "prosciutto", "serrano", "pastrami", "leverpastej", "medwurst", "ham", "cold cuts", "deli"),
            tags = listOf("en:hams", "en:salamis", "en:bacons", "en:cold-cuts", "en:sliced-meats", "en:prepared-meats", "en:liver-pates"),
        ),
        FoodCategory(
            "sausage", "Sausages", "Korv", "🌭", 7, 4, 2 * MONTH,
            keywords = listOf("korv", "falukorv", "prinskorv", "grillkorv", "chorizo", "salsiccia", "wienerkorv", "isterband", "sausage", "sausages", "hot dog", "frankfurter"),
            tags = listOf("en:sausages", "en:frankfurters"),
        ),
        FoodCategory(
            "milk", "Milk & cream", "Mjölk & grädde", "🥛", 7, 5, 3 * MONTH,
            keywords = listOf("mjölk", "mellanmjölk", "standardmjölk", "lättmjölk", "laktosfri mjölk", "grädde", "vispgrädde", "matlagningsgrädde", "havredryck", "havre dryck", "mandeldryck", "sojadryck", "milk", "cream", "oat drink"),
            tags = listOf("en:milks", "en:creams", "en:whole-milks", "en:semi-skimmed-milks", "en:skimmed-milks", "en:plant-based-milk-alternatives", "en:oat-based-drinks"),
        ),
        FoodCategory(
            "yoghurt", "Yoghurt & fil", "Yoghurt & fil", "🥣", 14, 7, 2 * MONTH,
            keywords = listOf("yoghurt", "yogurt", "fil", "filmjölk", "kvarg", "kesella", "gräddfil", "crème fraiche", "creme fraiche", "skyr", "turkisk", "smetana", "sour cream"),
            tags = listOf("en:yogurts", "en:fermented-milk-products", "en:quarks", "en:skyrs", "en:sour-creams", "en:creme-fraiche"),
        ),
        FoodCategory(
            "cheese", "Cheese", "Ost", "🧀", 60, 21, 6 * MONTH,
            keywords = listOf("ost", "hårdost", "prästost", "herrgård", "grevé", "präst", "cheddar", "parmesan", "parmigiano", "västerbotten", "gouda", "emmentaler", "manchego", "pecorino", "cheese"),
            tags = listOf("en:cheeses", "en:hard-cheeses", "en:semi-hard-cheeses", "en:cheddar", "en:parmigiano-reggiano", "en:goudas"),
        ),
        FoodCategory(
            "soft_cheese", "Soft & fresh cheese", "Färskost & mjukost", "🧀", 14, 7, 2 * MONTH,
            keywords = listOf("färskost", "mjukost", "mozzarella", "burrata", "brie", "camembert", "feta", "fetaost", "halloumi", "philadelphia", "ricotta", "keso", "mascarpone", "chèvre", "getost", "cream cheese", "cottage cheese"),
            tags = listOf("en:fresh-cheeses", "en:soft-cheeses", "en:cream-cheeses", "en:mozzarella", "en:fetas", "en:halloumi", "en:cottage-cheeses", "en:bries"),
        ),
        FoodCategory(
            "butter", "Butter & spreads", "Smör & margarin", "🧈", 60, 30, 6 * MONTH,
            keywords = listOf("smör", "bregott", "margarin", "lätta", "flora", "butter", "margarine"),
            tags = listOf("en:butters", "en:margarines", "en:spreadable-fats", "en:dairy-spreads"),
        ),
        FoodCategory(
            "eggs", "Eggs", "Ägg", "🥚", 28, 28, 0,
            keywords = listOf("ägg", "egg", "eggs"),
            tags = listOf("en:eggs", "en:chicken-eggs"),
        ),
        FoodCategory(
            "leftovers", "Leftovers", "Rester & matlådor", "🍲", 3, 3, 3 * MONTH, strict = true,
            keywords = listOf("rester", "matlåda", "matlådor", "lunchlåda", "leftover", "leftovers", "hemlagad"),
        ),
        FoodCategory(
            "ready_meal", "Ready meals & deli salads", "Färdigmat & sallader", "🥗", 3, 2, 3 * MONTH, strict = true,
            keywords = listOf("färdigrätt", "färdigmat", "pastasallad", "potatissallad", "sushi", "wrap", "smörgås", "sandwich", "ready meal", "pyttipanna", "lasagne", "pizza"),
            tags = listOf("en:meals", "en:prepared-salads", "en:sandwiches", "en:sushi", "en:pizzas", "en:lasagnas"),
        ),
        FoodCategory(
            "dips", "Dips & fresh sauces", "Dipp & färska såser", "🫙", 14, 5, 3 * MONTH,
            keywords = listOf("pesto", "hummus", "guacamole", "tzatziki", "dipp", "dip", "aioli", "bearnaise", "bearnaisesås", "röra", "skagenröra"),
            tags = listOf("en:dips", "en:pestos", "en:hummus", "en:tzatzikis", "en:guacamoles", "en:bearnaise-sauces"),
        ),
        FoodCategory(
            "sauces", "Sauces & dressings", "Såser & dressing", "🥫", 180, 60, 0,
            keywords = listOf("ketchup", "senap", "majonnäs", "dressing", "sås", "soja", "sojasås", "sriracha", "salsa", "chilisås", "grillsås", "mustard", "mayonnaise", "sauce"),
            tags = listOf("en:sauces", "en:condiments", "en:ketchup", "en:mustards", "en:mayonnaises", "en:salad-dressings"),
        ),
        FoodCategory(
            "greens", "Salad & herbs", "Sallad & örter", "🥬", 5, 3, 0,
            keywords = listOf("sallad", "isbergssallad", "ruccola", "rucola", "spenat", "babyspenat", "salladsmix", "örter", "basilika", "persilja", "dill", "koriander", "gräslök", "lettuce", "rocket", "spinach", "herbs", "salad"),
            tags = listOf("en:leaf-vegetables", "en:salads", "en:lettuces", "en:spinachs", "en:fresh-herbs", "en:aromatic-plants"),
        ),
        FoodCategory(
            "vegetables", "Vegetables", "Grönsaker", "🥕", 10, 4, 8 * MONTH,
            keywords = listOf("grönsaker", "morot", "morötter", "broccoli", "blomkål", "paprika", "gurka", "tomat", "tomater", "körsbärstomater", "zucchini", "squash", "purjolök", "kål", "vitkål", "rödkål", "sparris", "champinjoner", "svamp", "majs", "ärtor", "sockerärtor", "aubergine", "selleri", "rädisor", "carrot", "carrots", "cucumber", "tomato", "tomatoes", "pepper", "mushrooms", "cabbage", "vegetables"),
            tags = listOf("en:vegetables", "en:fresh-vegetables", "en:carrots", "en:tomatoes", "en:cucumbers", "en:mushrooms", "en:bell-peppers"),
        ),
        FoodCategory(
            "fruit", "Fruit", "Frukt", "🍎", 10, 3, 8 * MONTH,
            keywords = listOf("frukt", "äpple", "äpplen", "päron", "apelsin", "citron", "lime", "vindruvor", "druvor", "melon", "vattenmelon", "kiwi", "mango", "ananas", "persika", "nektarin", "plommon", "apple", "pear", "orange", "lemon", "grapes", "fruit"),
            tags = listOf("en:fruits", "en:fresh-fruits", "en:apples", "en:citrus", "en:grapes", "en:melons"),
        ),
        FoodCategory(
            "berries", "Berries", "Bär", "🍓", 4, 2, 10 * MONTH,
            keywords = listOf("bär", "jordgubbar", "hallon", "blåbär", "lingon", "björnbär", "vinbär", "berries", "strawberries", "raspberries", "blueberries"),
            tags = listOf("en:berries", "en:strawberries", "en:raspberries", "en:blueberries"),
        ),
        FoodCategory(
            "bread", "Bread", "Bröd", "🍞", 5, 5, 3 * MONTH, place = Place.Pantry,
            keywords = listOf("bröd", "limpa", "fralla", "frallor", "baguette", "tortilla", "tortillabröd", "pitabröd", "bullar", "kanelbullar", "rostbröd", "surdeg", "bread", "buns", "rolls"),
            tags = listOf("en:breads", "en:sliced-breads", "en:tortillas", "en:buns"),
        ),
        FoodCategory(
            "juice", "Juice", "Juice", "🧃", 14, 5, 8 * MONTH,
            keywords = listOf("juice", "apelsinjuice", "äppeljuice", "smoothie", "must", "juice drink"),
            tags = listOf("en:juices", "en:fruit-juices", "en:orange-juices", "en:apple-juices", "en:smoothies"),
        ),
        FoodCategory(
            "jars", "Pickles, jam & jars", "Inlagt, sylt & burkar", "🫙", 365, 60, 0,
            keywords = listOf("inlagd", "inlagda", "sill", "matjessill", "oliver", "kapris", "sylt", "marmelad", "saltgurka", "bostongurka", "smörgåsgurka", "jalapeños", "pickles", "olives", "jam"),
            tags = listOf("en:pickles", "en:jams", "en:marmalades", "en:olives", "en:pickled-herrings"),
        ),
        FoodCategory(
            "drinks", "Drinks", "Dryck", "🥤", 180, 3, 0,
            keywords = listOf("läsk", "cola", "öl", "vin", "cider", "mineralvatten", "kolsyrat", "lemonad", "soda", "beer", "wine"),
            tags = listOf("en:sodas", "en:beers", "en:wines", "en:waters", "en:carbonated-drinks", "en:ciders"),
        ),
        FoodCategory(
            "canned", "Canned food", "Konserver", "🥫", 730, 3, 0, place = Place.Pantry,
            keywords = listOf("konserv", "krossade tomater", "kokosmjölk", "tonfisk", "bönor", "kikärtor", "linser", "canned", "beans", "chickpeas", "coconut milk"),
            tags = listOf("en:canned-foods", "en:canned-vegetables", "en:canned-fishes", "en:canned-tomatoes", "en:legumes"),
        ),
        FoodCategory(
            "dry_goods", "Dry goods", "Torrvaror", "🌾", 365, 180, 0, place = Place.Pantry,
            keywords = listOf("pasta", "spaghetti", "ris", "mjöl", "vetemjöl", "socker", "havregryn", "müsli", "flingor", "knäckebröd", "nudlar", "couscous", "bulgur", "rice", "flour", "sugar", "oats", "cereal", "noodles"),
            tags = listOf("en:pastas", "en:rices", "en:flours", "en:sugars", "en:breakfast-cereals", "en:crispbreads", "en:noodles"),
        ),
        FoodCategory(
            "frozen", "Frozen food", "Fryst", "🧊", 1, 1, 9 * MONTH, place = Place.Freezer,
            keywords = listOf("fryst", "frysta", "djupfryst", "glass", "frozen", "ice cream"),
            tags = listOf("en:frozen-foods", "en:ice-creams", "en:frozen-desserts"),
        ),
        FoodCategory(
            "other", "Other", "Övrigt", "📦", 7, 4, 6 * MONTH,
        ),
    )

    val other: FoodCategory = all.last()

    private val byKey = all.associateBy { it.key }
    private val byTag: Map<String, FoodCategory> = all.flatMap { c -> c.tags.map { it to c } }.toMap()
    private val folded: List<Pair<String, FoodCategory>> = all.flatMap { c -> c.keywords.map { Text.fold(it) to c } }

    fun byKey(key: String?): FoodCategory = key?.let { byKey[it] } ?: other

    /** A category typed or said in Swedish or English ("mejeri" won't do, "Mjölk & grädde" or "milk" will). */
    fun find(name: String): FoodCategory? {
        val q = Text.fold(name)
        if (q.isBlank()) return null
        return all.firstOrNull { it.key == q || Text.fold(it.en) == q || Text.fold(it.sv) == q }
            ?: all.firstOrNull { Text.fold(it.en).contains(q) || Text.fold(it.sv).contains(q) }
            ?: classify(name).takeIf { it != other }
    }

    /**
     * The best guess at what [name] is. Open Food Facts' [tags] are tried most specific first
     * (they come general to specific); then words in the name, the longest match winning so
     * "kycklingfärs" is mince rather than chicken.
     */
    fun classify(name: String, tags: List<String> = emptyList()): FoodCategory {
        tags.asReversed().firstNotNullOfOrNull { byTag[it] }?.let { return it }
        val text = Text.fold(name)
        if (text.isBlank()) return other
        var best: FoodCategory? = null
        var bestLength = 0
        for ((word, category) in folded) {
            if (word.length > bestLength && matches(text, word)) {
                best = category
                bestLength = word.length
            }
        }
        return best ?: other
    }

    /**
     * Whether [word] is in [text]. Swedish runs words together ("nötfärs", "prästost"), so a
     * longer word may sit anywhere; a short one must start or end a word, so "ost" finds
     * "getost" but not "rostbiff", and one of two letters ("öl") must be a word of its own.
     */
    private fun matches(text: String, word: String): Boolean {
        var at = text.indexOf(word)
        while (at >= 0) {
            val end = at + word.length
            val startsWord = at == 0 || !text[at - 1].isLetterOrDigit()
            val endsWord = end == text.length || !text[end].isLetterOrDigit()
            val ok = when {
                word.length <= 2 -> startsWord && endsWord
                word.length <= 4 -> startsWord || endsWord
                else -> true
            }
            if (ok) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }

    /** When [item] should be eaten by, on [today]. */
    fun estimate(item: FridgeItem, today: LocalDate): Estimate {
        val category = byKey(item.category)
        val bought = item.boughtDate() ?: today
        if (item.place == Place.Freezer) {
            val frozen = item.frozenDate() ?: bought
            val date = frozen.plusDays((category.freezerDays.takeIf { it > 0 } ?: 6 * MONTH).toLong())
            return Estimate(date, Basis.Frozen, strict = false, daysLeft = ChronoUnit.DAYS.between(today, date))
        }
        val days = item.keeps ?: category.days
        val openedDays = item.keepsOpened ?: category.openedDays
        val strict = when (item.dateKind) {
            DateKind.UseBy -> true
            DateKind.BestBefore -> false
            null -> category.strict
        }
        // Out of the freezer: the clock started again on thawing, and thawed food keeps a day or two.
        item.thawedDate()?.let { thawed ->
            val date = thawed.plusDays(openedDays.coerceIn(1, 2).toLong())
            return Estimate(date, Basis.Thawed, strict, ChronoUnit.DAYS.between(today, date))
        }
        val printed = item.printedDate()
        var date = printed ?: bought.plusDays(days.toLong())
        var basis = when {
            printed != null -> Basis.Printed
            item.keeps != null -> Basis.Product
            else -> Basis.Guessed
        }
        item.openedDate()?.let { opened ->
            val limit = opened.plusDays(openedDays.toLong())
            if (limit < date) {
                date = limit
                basis = Basis.Opened
            }
        }
        return Estimate(date, basis, strict, ChronoUnit.DAYS.between(today, date))
    }
}
