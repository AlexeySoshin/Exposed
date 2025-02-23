package org.jetbrains.exposed.sql.tests.shared.dml

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.tests.DatabaseTestsBase
import org.jetbrains.exposed.sql.tests.TestDB
import org.jetbrains.exposed.sql.tests.currentDialectTest
import org.jetbrains.exposed.sql.tests.shared.assertEqualLists
import org.jetbrains.exposed.sql.tests.shared.assertEquals
import org.jetbrains.exposed.sql.tests.shared.assertFailAndRollback
import org.jetbrains.exposed.sql.tests.shared.assertTrue
import org.jetbrains.exposed.sql.tests.shared.entities.EntityTests
import org.jetbrains.exposed.sql.tests.shared.expectException
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.junit.Assume
import org.junit.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

class InsertTests : DatabaseTestsBase() {
    @Test
    fun testInsertAndGetId01() {
        val idTable = object : IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(idTable) {
            idTable.insertAndGetId {
                it[idTable.name] = "1"
            }

            assertEquals(1L, idTable.selectAll().count())

            idTable.insertAndGetId {
                it[idTable.name] = "2"
            }

            assertEquals(2L, idTable.selectAll().count())

            assertFailAndRollback("Unique constraint") {
                idTable.insertAndGetId {
                    it[idTable.name] = "2"
                }
            }
        }
    }

    private val insertIgnoreSupportedDB = TestDB.values().toList() -
        listOf(TestDB.SQLITE, TestDB.MYSQL, TestDB.H2_MYSQL, TestDB.POSTGRESQL, TestDB.POSTGRESQLNG, TestDB.H2_PSQL)

    @Test
    fun testInsertIgnoreAndGetId01() {
        val idTable = object : IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(insertIgnoreSupportedDB, idTable) {
            idTable.insertIgnoreAndGetId {
                it[idTable.name] = "1"
            }

            assertEquals(1L, idTable.selectAll().count())

            idTable.insertIgnoreAndGetId {
                it[idTable.name] = "2"
            }

            assertEquals(2L, idTable.selectAll().count())

            val idNull = idTable.insertIgnoreAndGetId {
                it[idTable.name] = "2"
            }

            assertEquals(null, idNull)

            val shouldNotReturnProvidedIdOnConflict = idTable.insertIgnoreAndGetId {
                it[idTable.id] = EntityID(100, idTable)
                it[idTable.name] = "2"
            }

            assertEquals(null, shouldNotReturnProvidedIdOnConflict)
        }
    }

    @Test
    fun `test insert and get id when column has different name and get value by id column`() {
        val testTableWithId = object : IdTable<Int>("testTableWithId") {
            val code = integer("code")
            override val id: Column<EntityID<Int>> = code.entityId()
        }

        withTables(testTableWithId) {
            val id1 = testTableWithId.insertAndGetId {
                it[code] = 1
            }
            assertNotNull(id1)
            assertEquals(1, id1.value)

            val id2 = testTableWithId.insert {
                it[code] = 2
            } get testTableWithId.id
            assertNotNull(id2)
            assertEquals(2, id2.value)
        }
    }

    @Test
    fun `test id and column have different names and get value by original column`() {
        val exampleTable = object : IdTable<String>("test_id_and_column_table") {
            val exampleColumn = varchar("example_column", 200)
            override val id = exampleColumn.entityId()
        }

        withTables(exampleTable) {
            val value = "value"
            exampleTable.insert {
                it[exampleColumn] = value
            }

            val resultValues: List<String> = exampleTable.selectAll().map { it[exampleTable.exampleColumn] }

            assertEquals(value, resultValues.first())
        }
    }

    @Test
    fun testInsertIgnoreAndGetIdWithPredefinedId() {
        val idTable = object : IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        val insertIgnoreSupportedDB = TestDB.values().toList() -
            listOf(TestDB.SQLITE, TestDB.MYSQL, TestDB.H2_MYSQL, TestDB.POSTGRESQL, TestDB.POSTGRESQLNG, TestDB.H2_PSQL)

        withTables(insertIgnoreSupportedDB, idTable) {
            val insertedStatement = idTable.insertIgnore {
                it[idTable.id] = EntityID(1, idTable)
                it[idTable.name] = "1"
            }
            assertEquals(1, insertedStatement[idTable.id].value)
            assertEquals(1, insertedStatement.insertedCount)

            val notInsertedStatement = idTable.insertIgnore {
                it[idTable.id] = EntityID(1, idTable)
                it[idTable.name] = "2"
            }

            assertEquals(1, notInsertedStatement[idTable.id].value)
            assertEquals(0, notInsertedStatement.insertedCount)
        }
    }

    @Test
    fun testBatchInsert01() {
        withCitiesAndUsers { cities, users, _ ->
            val cityNames = listOf("Paris", "Moscow", "Helsinki")
            val allCitiesID = cities.batchInsert(cityNames) { name ->
                this[cities.name] = name
            }
            assertEquals(cityNames.size, allCitiesID.size)

            val userNamesWithCityIds = allCitiesID.mapIndexed { index, id ->
                "UserFrom${cityNames[index]}" to id[cities.id] as Number
            }

            val generatedIds = users.batchInsert(userNamesWithCityIds) { (userName, cityId) ->
                this[users.id] = java.util.Random().nextInt().toString().take(6)
                this[users.name] = userName
                this[users.cityId] = cityId.toInt()
            }

            assertEquals(userNamesWithCityIds.size, generatedIds.size)
            assertEquals(userNamesWithCityIds.size.toLong(), users.select { users.name inList userNamesWithCityIds.map { it.first } }.count())
        }
    }

    @Test
    fun `batchInserting using a sequence should work`() {
        val Cities = DMLTestsData.Cities
        withTables(Cities) {
            val names = List(25) { UUID.randomUUID().toString() }.asSequence()
            Cities.batchInsert(names) { name -> this[Cities.name] = name }

            val batchesSize = Cities.selectAll().count()

            assertEquals(25, batchesSize)
        }
    }

    @Test
    fun `batchInserting using empty sequence should work`() {
        val Cities = DMLTestsData.Cities
        withTables(Cities) {
            val names = emptySequence<String>()
            Cities.batchInsert(names) { name -> this[Cities.name] = name }

            val batchesSize = Cities.selectAll().count()

            assertEquals(0, batchesSize)
        }
    }

    @Test
    fun testGeneratedKey01() {
        withTables(DMLTestsData.Cities) {
            val id = DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "FooCity"
            } get DMLTestsData.Cities.id
            assertEquals(DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.id], id)
        }
    }

    object LongIdTable : Table() {
        val id = long("id").autoIncrement()
        val name = text("name")

        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun testGeneratedKey02() {
        withTables(LongIdTable) {
            val id = LongIdTable.insert {
                it[LongIdTable.name] = "Foo"
            } get LongIdTable.id
            assertEquals(LongIdTable.selectAll().last()[LongIdTable.id], id)
        }
    }

    object IntIdTestTable : IntIdTable() {
        val name = text("name")
    }

    @Test
    fun testGeneratedKey03() {
        withTables(IntIdTestTable) {
            val id = IntIdTestTable.insertAndGetId {
                it[IntIdTestTable.name] = "Foo"
            }
            assertEquals(IntIdTestTable.selectAll().last()[IntIdTestTable.id], id)
        }
    }

    @Test fun testInsertWithPredefinedId() {
        val stringTable = object : IdTable<String>("stringTable") {
            override val id = varchar("id", 15).entityId()
            val name = varchar("name", 10)
        }
        withTables(stringTable) {
            val entityID = EntityID("id1", stringTable)
            val id1 = stringTable.insertAndGetId {
                it[id] = entityID
                it[name] = "foo"
            }

            stringTable.insertAndGetId {
                it[id] = EntityID("testId", stringTable)
                it[name] = "bar"
            }

            assertEquals(id1, entityID)
            val row1 = stringTable.select { stringTable.id eq entityID }.singleOrNull()
            assertEquals(row1?.get(stringTable.id), entityID)

            val row2 = stringTable.select { stringTable.id like "id%" }.singleOrNull()
            assertEquals(row2?.get(stringTable.id), entityID)
        }
    }

    @Test fun testInsertWithExpression() {

        val tbl = object : IntIdTable("testInsert") {
            val nullableInt = integer("nullableIntCol").nullable()
            val string = varchar("stringCol", 20)
        }

        fun expression(value: String) = stringLiteral(value).trim().substring(2, 4)

        fun verify(value: String) {
            val row = tbl.select { tbl.string eq value }.single()
            assertEquals(row[tbl.string], value)
        }

        withTables(tbl) {
            tbl.insert {
                it[string] = expression(" _exp1_ ")
            }

            verify("exp1")

            tbl.insert {
                it[string] = expression(" _exp2_ ")
                it[nullableInt] = 5
            }

            verify("exp2")

            tbl.insert {
                it[string] = expression(" _exp3_ ")
                it[nullableInt] = null
            }

            verify("exp3")
        }
    }

    @Test fun testInsertWithColumnExpression() {

        val tbl1 = object : IntIdTable("testInsert1") {
            val string1 = varchar("stringCol", 20)
        }
        val tbl2 = object : IntIdTable("testInsert2") {
            val string2 = varchar("stringCol", 20).nullable()
        }

        fun verify(value: String) {
            val row = tbl2.select { tbl2.string2 eq value }.single()
            assertEquals(row[tbl2.string2], value)
        }

        withTables(tbl1, tbl2) {
            val id = tbl1.insertAndGetId {
                it[string1] = " _exp1_ "
            }

            val expr1 = tbl1.string1.trim().substring(2, 4)
            tbl2.insert {
                it[string2] = wrapAsExpression(tbl1.slice(expr1).select { tbl1.id eq id })
            }

            verify("exp1")
        }
    }

    private object OrderedDataTable : IntIdTable() {
        val name = text("name")
        val order = integer("order")
    }

    class OrderedData(id: EntityID<Int>) : IntEntity(id) {
        companion object : IntEntityClass<OrderedData>(OrderedDataTable)

        var name by OrderedDataTable.name
        var order by OrderedDataTable.order
    }

    // https://github.com/JetBrains/Exposed/issues/192
    @Test fun testInsertWithColumnNamedWithKeyword() {
        withTables(OrderedDataTable) {

            val foo = OrderedData.new {
                name = "foo"
                order = 20
            }
            val bar = OrderedData.new {
                name = "bar"
                order = 10
            }

            assertEqualLists(listOf(bar, foo), OrderedData.all().orderBy(OrderedDataTable.order to SortOrder.ASC).toList())
        }
    }

    @Test fun testInsertEmojis() {
        val table = object : Table("tmp") {
            val emoji = varchar("emoji", 16)
        }
        val emojis = "\uD83D\uDC68\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF"

        withTables(TestDB.allH2TestDB + TestDB.SQLSERVER + TestDB.ORACLE, table) {
            val isOldMySQL = currentDialectTest is MysqlDialect && db.isVersionCovers(BigDecimal("5.5"))
            if (isOldMySQL) {
                exec("ALTER TABLE ${table.nameInDatabaseCase()} DEFAULT CHARSET utf8mb4, MODIFY emoji VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
            }
            table.insert {
                it[table.emoji] = emojis
            }

            assertEquals(1L, table.selectAll().count())
        }
    }

    @Test fun testInsertEmojisWithInvalidLength() {
        val table = object : Table("tmp") {
            val emoji = varchar("emoji", 10)
        }
        val emojis = "\uD83D\uDC68\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF"

        withTables(listOf(TestDB.SQLITE, TestDB.H2, TestDB.H2_MYSQL, TestDB.POSTGRESQL, TestDB.POSTGRESQLNG, TestDB.H2_PSQL), table) {
            expectException<IllegalArgumentException> {
                table.insert {
                    it[table.emoji] = emojis
                }
            }
        }
    }

    @Test
    fun `test that column length checked on insert`() {
        val stringTable = object : IntIdTable("StringTable") {
            val name = varchar("name", 10)
        }

        withTables(stringTable) {
            val veryLongString = "1".repeat(255)
            expectException<IllegalArgumentException> {
                stringTable.insert {
                    it[name] = veryLongString
                }
            }
        }
    }

    @Test fun `test subquery in an insert or update statement`() {
        val tab1 = object : Table("tab1") {
            val id = varchar("id", 10)
        }
        val tab2 = object : Table("tab2") {
            val id = varchar("id", 10)
        }

        withTables(tab1, tab2) {
            // Initial data
            tab2.insert { it[id] = "foo" }
            tab2.insert { it[id] = "bar" }

            // Use sub query in an insert
            tab1.insert { it[id] = tab2.slice(tab2.id).select { tab2.id eq "foo" } }

            // Check inserted data
            val insertedId = tab1.slice(tab1.id).selectAll().single()[tab1.id]
            assertEquals("foo", insertedId)

            // Use sub query in an update
            tab1.update({ tab1.id eq "foo" }) { it[id] = tab2.slice(tab2.id).select { tab2.id eq "bar" } }

            // Check updated data
            val updatedId = tab1.slice(tab1.id).selectAll().single()[tab1.id]
            assertEquals("bar", updatedId)
        }
    }

    @Test fun testGeneratedKey04() {
        val CharIdTable = object : IdTable<String>("charId") {
            override val id = varchar("id", 50)
                    .clientDefault { UUID.randomUUID().toString() }
                    .entityId()
            val foo = integer("foo")

            override val primaryKey: PrimaryKey = PrimaryKey(id)
        }
        withTables(CharIdTable) {
            val id = CharIdTable.insertAndGetId {
                it[CharIdTable.foo] = 5
            }
            assertNotNull(id.value)
        }
    }

    @Test fun `rollback on constraint exception normal transactions`() {
        val TestTable = object : IntIdTable("TestRollback") {
            val foo = integer("foo").check { it greater 0 }
        }
        val dbToTest = TestDB.enabledInTests() - setOfNotNull(
            TestDB.SQLITE,
            TestDB.MYSQL.takeIf { System.getProperty("exposed.test.mysql8.port") == null }
        )
        Assume.assumeTrue(dbToTest.isNotEmpty())
        dbToTest.forEach { db ->
            try {
                try {
                    withDb(db) {
                        SchemaUtils.create(TestTable)
                        TestTable.insert { it[foo] = 1 }
                        TestTable.insert { it[foo] = 0 }
                    }
                    fail("Should fail on constraint > 0 with $db")
                } catch (_: SQLException) {
                    // expected
                }
                withDb(db) {
                    assertTrue(TestTable.selectAll().empty())
                }
            } finally {
                withDb(db) {
                    SchemaUtils.drop()
                }
            }
        }
    }

    @Test fun `rollback on constraint exception normal suspended transactions`() {
        val TestTable = object : IntIdTable("TestRollback") {
            val foo = integer("foo").check { it greater 0 }
        }
        val dbToTest = TestDB.enabledInTests() - setOfNotNull(
            TestDB.SQLITE,
            TestDB.MYSQL.takeIf { System.getProperty("exposed.test.mysql8.port") == null }
        )
        Assume.assumeTrue(dbToTest.isNotEmpty())
        dbToTest.forEach { db ->
            try {
                try {
                    withDb(db) {
                        SchemaUtils.create(TestTable)
                    }
                    runBlocking {
                        newSuspendedTransaction(db = db.db) {
                            TestTable.insert { it[foo] = 1 }
                            TestTable.insert { it[foo] = 0 }
                        }
                    }
                    fail("Should fail on constraint > 0")
                } catch (_: SQLException) {
                    // expected
                }

                withDb(db) {
                    assertTrue(TestTable.selectAll().empty())
                }
            } finally {
                withDb(db) {
                    SchemaUtils.drop()
                }
            }
        }
    }

    @Test fun `test optReference allows null values`() {
        withTables(EntityTests.Posts) {
            val id1 = EntityTests.Posts.insertAndGetId {
                it[board] = null
                it[category] = null
            }

            val inserted1 = EntityTests.Posts.select { EntityTests.Posts.id eq id1 }.single()
            assertNull(inserted1[EntityTests.Posts.board])
            assertNull(inserted1[EntityTests.Posts.category])

            val boardId = EntityTests.Boards.insertAndGetId {
                it[name] = UUID.randomUUID().toString()
            }
            val categoryId = EntityTests.Categories.insert {
                it[title] = "Category"
            }[EntityTests.Categories.uniqueId]

            val id2 = EntityTests.Posts.insertAndGetId {
                it[board] = Op.nullOp()
                it[category] = categoryId
                it[board] = boardId.value
            }

            EntityTests.Posts.deleteWhere { EntityTests.Posts.id eq id2 }

            val nullableCategoryID: UUID? = categoryId
            val nullableBoardId: Int? = boardId.value
            EntityTests.Posts.insertAndGetId {
                it[board] = Op.nullOp()
                it[category] = nullableCategoryID
                it[board] = nullableBoardId
            }
        }

    }
}
