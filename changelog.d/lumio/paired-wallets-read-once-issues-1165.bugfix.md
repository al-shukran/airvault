Wallets are read from the vault once per write, with one Keystore unwrap per wallet, so Accounts no longer waits on repeated reads after unlock.
