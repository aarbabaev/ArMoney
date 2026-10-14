# Demonstration ArMoney policy: currency
These are demonstration rules derived from implemented ArMoney behavior at main revision 112b01d90c247bd774db7fa9f9720edcc0b439f8. They are not production or legal bank policies. ArMoney supports AED only. Money is recorded as integer fils; 100 fils equal 1 AED. Currency conversion is not supported.

# Demonstration ArMoney policy: balances and transfers
The ledger owns balance correctness. A payment is completed only after the ledger confirms an atomic, balanced posting. Concurrent transfers cannot spend more than the available balance. A rejected payment is not a completed transfer. The assistant can read balances and explain rules; it cannot execute transfers.

# Demonstration ArMoney policy: transaction visibility
The last transaction means the first visible payment ordered by creation time descending and payment ID descending. Senders see their pending, completed and rejected payment intents. Recipients see only completed incoming payments. Empty history contains no transaction. An outage must not appear as zero balance or a successful payment.

# Demonstration ArMoney policy: privacy
Assistant conversations are request-local and are not stored by ArMoney's assistant service. Policy excerpts and minimized questions may be sent to the configured external OpenAI provider. The Responses API uses store=false. This does not guarantee zero provider retention; abuse monitoring may retain content for up to 30 days, subject to account controls. The assistant has no upload, arbitrary URL, SQL, shell or payment execution tool.
