package com.cookpad.android.licensetools;

class NotEnoughInformationException extends RuntimeException {
    public final LibraryInfo libraryInfo;

    NotEnoughInformationException(LibraryInfo libraryInfo) {
        this.libraryInfo = libraryInfo;
    }
}
