package com.arman.bank.walletservice

import com.arman.bank.walletservice.application.WalletService
import com.arman.bank.walletservice.application.WalletStore
import com.arman.bank.walletservice.domain.Wallet
import spock.lang.Specification

class WalletCurrencySpec extends Specification {
    def "only AED is accepted for wallet creation and owner currency lookup"() {
        given:
        def store = Mock(WalletStore)
        def service = new WalletService(store)
        def owner = UUID.randomUUID()

        when:
        service.open(owner, currency)
        then:
        thrown(IllegalArgumentException)
        0 * store._

        when:
        service.find(owner, currency)
        then:
        thrown(IllegalArgumentException)
        0 * store._

        where:
        currency << ['USD', 'EUR', 'GBP', 'aed', 'XYZ', '', 'AE', 'AEDD', null]
    }

    def "AED wallet preserves identity and pending provisioning"() {
        given:
        def id = UUID.randomUUID()
        def owner = UUID.randomUUID()
        when:
        def wallet = new Wallet(id, owner, 'AED', 'ACTIVE')
        then:
        wallet.id() == id
        wallet.ownerId() == owner
        wallet.currency() == 'AED'
        wallet.provisioningStatus() == 'PENDING'
        wallet.ledgerAccountId() == null
    }
}
