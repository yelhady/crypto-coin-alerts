package com.alexitc.coinalerts.data.anorm

import com.alexitc.coinalerts.commons.DataHelper._
import com.alexitc.coinalerts.commons.{PostgresDataHandlerSpec, RandomDataGenerator}
import com.alexitc.coinalerts.core.{Count, Limit, Offset, PaginatedQuery}
import com.alexitc.coinalerts.data.anorm.dao.{ExchangeCurrencyPostgresDAO, FixedPriceAlertPostgresDAO}
import com.alexitc.coinalerts.data.anorm.interpreters.FixedPriceAlertFilterSQLInterpreter
import com.alexitc.coinalerts.errors.{FixedPriceAlertNotFoundError, InvalidPriceError, UnknownExchangeCurrencyIdError, VerifiedUserNotFound}
import com.alexitc.coinalerts.models.FixedPriceAlertFilter._
import com.alexitc.coinalerts.models._
import org.scalactic.{Bad, Good}

class FixedPriceAlertPostgresDataHandlerSpec extends PostgresDataHandlerSpec {

  lazy val alertPostgresDataHandler = new FixedPriceAlertPostgresDataHandler(
    database,
    new ExchangeCurrencyPostgresDAO,
    new FixedPriceAlertPostgresDAO(new FixedPriceAlertFilterSQLInterpreter))

  lazy val verifiedUser = createVerifiedUser()

  lazy val currencies = exchangeCurrencyDataHandler.getAll().get
  lazy val createDefaultAlertModel = CreateFixedPriceAlertModel(RandomDataGenerator.item(currencies).id, true, BigDecimal("5000.00"), None)
  lazy val createBasePriceAlertModel = createDefaultAlertModel.copy(basePrice = Some(BigDecimal("4000.00")))

  def justThisUserCondition(userId: UserId) = {
    Conditions(AnyTriggeredCondition, JustThisUserCondition(userId))
  }

  "Creating an alert" should {

    "ba able to create an alert without basePrice" in {
      val result = alertPostgresDataHandler.create(createDefaultAlertModel, verifiedUser.id)
      result.isGood mustEqual true
    }

    "be able to create an alert with basePrice" in {
      val result = alertPostgresDataHandler.create(createBasePriceAlertModel, verifiedUser.id)
      result.isGood mustEqual true
    }

    "fail to create an alert for a non existent user" in {
      val result = alertPostgresDataHandler.create(createDefaultAlertModel, UserId.create)
      result mustEqual Bad(VerifiedUserNotFound).accumulating
    }

    "fail to create an alert for an unknown exchangeCurrencyId" in {
      val exchangeCurrencyId = ExchangeCurrencyId(currencies.map(_.id.int).max + 1)
      val model = createDefaultAlertModel.copy(exchangeCurrencyId = exchangeCurrencyId)
      val result = alertPostgresDataHandler.create(model, verifiedUser.id)
      result mustEqual Bad(UnknownExchangeCurrencyIdError).accumulating
    }
  }

  "markAsTriggered" should {
    "mark an existing alert as triggered" in {
      val user = createUnverifiedUser()
      val alert = alertPostgresDataHandler.create(RandomDataGenerator.createFixedPriceAlertModel(RandomDataGenerator.item(currencies).id), user.id).get
      val result = alertPostgresDataHandler.markAsTriggered(alert.id)
      result.isGood mustEqual true
    }

    "fail to mark an already triggered alert" in {
      val user = createUnverifiedUser()
      val alert = alertPostgresDataHandler.create(RandomDataGenerator.createFixedPriceAlertModel(RandomDataGenerator.item(currencies).id), user.id).get
      alertPostgresDataHandler.markAsTriggered(alert.id)

      val result = alertPostgresDataHandler.markAsTriggered(alert.id)
      result mustEqual Bad(FixedPriceAlertNotFoundError).accumulating
    }

    "fail to mark a non existent alert" in {
      val result = alertPostgresDataHandler.markAsTriggered(RandomDataGenerator.alertId)
      result mustEqual Bad(FixedPriceAlertNotFoundError).accumulating
    }
  }

  "findAlertsByPrice" should {

    "retrieve an alert requiring the current price to be greater than the given price" in {
      val user = createUnverifiedUser()
      val givenPrice = BigDecimal("1000")
      val createModel = RandomDataGenerator.createFixedPriceAlertModel(exchangeCurrencyId = RandomDataGenerator.item(currencies).id, givenPrice = givenPrice, isGreaterThan = true)
      val alert = alertPostgresDataHandler.create(createModel, user.id).get
      val currentPrice = BigDecimal("1000.00000001")
      val result = alertPostgresDataHandler.findPendingAlertsForPrice(createModel.exchangeCurrencyId, currentPrice).get
      result.exists(_.id == alert.id) mustEqual true
    }

    "retrieve an alert requiring the current price to be lower than the given price" in {
      val user = createUnverifiedUser()
      val givenPrice = BigDecimal("1000")
      val createModel = RandomDataGenerator.createFixedPriceAlertModel(exchangeCurrencyId = RandomDataGenerator.item(currencies).id, givenPrice = givenPrice, isGreaterThan = false)
      val alert = alertPostgresDataHandler.create(createModel, user.id).get
      val currentPrice = BigDecimal("999.99999999")
      val result = alertPostgresDataHandler.findPendingAlertsForPrice(createModel.exchangeCurrencyId, currentPrice).get
      result.exists(_.id == alert.id) mustEqual true
    }

    // TODO: Fix non-deterministic test
    "retrieve several pending alerts" in {
      val createAlertModel = CreateFixedPriceAlertModel(RandomDataGenerator.item(currencies).id, true, BigDecimal("5000.00"), None)
      val createAlert1 = createAlertModel
      val createAlert2 = createAlertModel.copy(isGreaterThan = false)
      val createAlert3 = createAlertModel.copy(exchangeCurrencyId = RandomDataGenerator.item(currencies).id)
      val createAlert4 = createAlertModel.copy(exchangeCurrencyId = RandomDataGenerator.item(currencies).id)

      val user = createUnverifiedUser()
      val alert1 = alertPostgresDataHandler.create(createAlert1, user.id).get
      val alert2 = alertPostgresDataHandler.create(createAlert2, user.id).get
      val alert3 = alertPostgresDataHandler.create(createAlert3, user.id).get
      val alert4 = alertPostgresDataHandler.create(createAlert4, user.id).get

      val result1 = alertPostgresDataHandler.findPendingAlertsForPrice(createAlert1.exchangeCurrencyId, BigDecimal("5000.00000001")).get
      result1.exists(_.id == alert1.id) mustEqual true
      result1.exists(_.id == alert2.id) mustEqual false
      result1.exists(_.id == alert3.id) mustEqual false
      result1.exists(_.id == alert4.id) mustEqual false

      val result2 = alertPostgresDataHandler.findPendingAlertsForPrice(createAlert2.exchangeCurrencyId, BigDecimal("4999.99999999")).get
      result2.exists(_.id == alert1.id) mustEqual false
      result2.exists(_.id == alert2.id) mustEqual true
      result2.exists(_.id == alert3.id) mustEqual false
      result2.exists(_.id == alert4.id) mustEqual false
    }

    "not retrieve an alert that is already triggered" in {
      val user = createUnverifiedUser()
      val givenPrice = BigDecimal("1000")
      val createModel = RandomDataGenerator.createFixedPriceAlertModel(exchangeCurrencyId = RandomDataGenerator.item(currencies).id, givenPrice = givenPrice)
      val alert = alertPostgresDataHandler.create(createModel, user.id).get
      alertPostgresDataHandler.markAsTriggered(alert.id)

      val result = alertPostgresDataHandler.findPendingAlertsForPrice(createModel.exchangeCurrencyId, givenPrice).get
      result.exists(_.id == alert.id) mustEqual false
    }

    "fail to filter by negative price" in {
      val currentPrice = BigDecimal("0")
      val result = alertPostgresDataHandler.findPendingAlertsForPrice(RandomDataGenerator.item(currencies).id, currentPrice)
      result mustEqual Bad(InvalidPriceError).accumulating
    }
  }

  "retrieving and filtering alerts" should {
    "return empty result for non-existent user" in {
      val userId = UserId.create
      val query = PaginatedQuery(Offset(0), Limit(10))
      val result = alertPostgresDataHandler.getAlerts(justThisUserCondition(userId), query).get
      result.data.isEmpty mustEqual true
      result.total mustEqual Count(0)
    }

    "return empty result when the offset is greater than the total elements" in {
      val user = createUnverifiedUser()
      alertPostgresDataHandler.create(createDefaultAlertModel, user.id)

      val query = PaginatedQuery(Offset(1), Limit(1))
      val result = alertPostgresDataHandler.getAlerts(justThisUserCondition(user.id), query).get
      result.data.isEmpty mustEqual true
      result.total mustEqual Count(1)
    }

    "return a result that is paginated properly" in {
      val user = createUnverifiedUser()
      alertPostgresDataHandler.create(createDefaultAlertModel, user.id)
      alertPostgresDataHandler.create(createDefaultAlertModel, user.id)

      val query = PaginatedQuery(Offset(0), Limit(1))
      val result = alertPostgresDataHandler.getAlerts(justThisUserCondition(user.id), query).get
      result.offset mustEqual query.offset
      result.limit mustEqual query.limit
      result.total mustEqual Count(2)
      result.data.length mustEqual query.limit.int
    }

    "return a result for the second page different to the one on the first page" in {
      val user = createUnverifiedUser()
      alertPostgresDataHandler.create(createDefaultAlertModel, user.id)
      alertPostgresDataHandler.create(createDefaultAlertModel, user.id)

      val page1Query = PaginatedQuery(Offset(0), Limit(1))
      val page1Result = alertPostgresDataHandler.getAlerts(justThisUserCondition(user.id), page1Query).get

      val page2Query = PaginatedQuery(Offset(1), Limit(1))
      val page2Result = alertPostgresDataHandler.getAlerts(justThisUserCondition(user.id), page2Query).get

      page1Result.data.head.id mustNot be(page2Result.data.head.id)
    }

    "return non-triggered alerts only" in {
      val user = createUnverifiedUser()
      val nonTriggeredAlert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get
      val triggeredAlert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get
      alertPostgresDataHandler.markAsTriggered(triggeredAlert.id)

      val conditions = Conditions(HasNotBeenTriggeredCondition, JustThisUserCondition(user.id))
      val query = PaginatedQuery(Offset(0), Limit(10))
      val result = alertPostgresDataHandler.getAlerts(conditions, query).get

      result.data.length mustEqual 1
      result.data.head.id mustEqual nonTriggeredAlert.id
      result.total mustEqual Count(1)
    }

    "return triggered alerts only" in {
      val user = createUnverifiedUser()
      val nonTriggeredAlert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get
      val triggeredAlert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get
      alertPostgresDataHandler.markAsTriggered(triggeredAlert.id)

      val conditions = Conditions(HasBeenTriggeredCondition, JustThisUserCondition(user.id))
      val query = PaginatedQuery(Offset(0), Limit(10))
      val result = alertPostgresDataHandler.getAlerts(conditions, query).get

      result.data.length mustEqual 1
      result.data.head.id mustEqual triggeredAlert.id
      result.total mustEqual Count(1)
    }
  }

  "counting user alerts" should {
    "return the number of alerts for a user" in {
      val user1 = createUnverifiedUser()
      alertPostgresDataHandler.create(createDefaultAlertModel, user1.id)
      alertPostgresDataHandler.create(createDefaultAlertModel, user1.id)

      val user2 = createUnverifiedUser()
      alertPostgresDataHandler.create(createDefaultAlertModel, user2.id)

      val result = alertPostgresDataHandler.countBy(justThisUserCondition(user1.id)).get
      result mustEqual Count(2)
    }
  }

  "deleting an alert" should {
    "delete it" in {
      val user = createVerifiedUser()
      val alert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get

      val result = alertPostgresDataHandler.delete(alert.id, user.id)
      result mustEqual Good(alert)
    }

    "fail when the alert doesn't exists" in {
      val user = createVerifiedUser()
      val allAlertsCondition = Conditions(AnyTriggeredCondition, AnyUserCondition)
      val query = PaginatedQuery(Offset(0), Limit(1000000))
      val allAlerts = alertPostgresDataHandler.getAlerts(allAlertsCondition, query).get.data
      val nonExistentId = FixedPriceAlertId(allAlerts.map(_.id.long).max + 1)

      val result = alertPostgresDataHandler.delete(nonExistentId, user.id)
      result mustEqual Bad(FixedPriceAlertNotFoundError).accumulating
    }

    "fail when the alert doesn't belongs to the user" in {
      val user1 = createVerifiedUser()
      val user2 = createVerifiedUser()
      val alert = alertPostgresDataHandler.create(createDefaultAlertModel, user1.id).get

      val result = alertPostgresDataHandler.delete(alert.id, user2.id)
      result mustEqual Bad(FixedPriceAlertNotFoundError).accumulating
    }

    "fail to delete an already triggered alert" in {
      val user = createVerifiedUser()
      val alert = alertPostgresDataHandler.create(createDefaultAlertModel, user.id).get
      alertPostgresDataHandler.markAsTriggered(alert.id)

      val result = alertPostgresDataHandler.delete(alert.id, user.id)
      result mustEqual Bad(FixedPriceAlertNotFoundError).accumulating
    }
  }
}
