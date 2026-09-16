package com.costonomy.mp.storage.provider;

/** The store refused or could not be reached. Never carries a provider payload. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
