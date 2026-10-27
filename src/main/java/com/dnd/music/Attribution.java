package com.dnd.music;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Who made a track or sound and under which license it may be used, e.g.
 * "Master of the Feast" by Kevin MacLeod, CC BY 3.0. Shown in the music panel's credits.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Attribution {
    private String title;
    private String author;
    /** Short license name, e.g. "CC BY 3.0" or "CC0 1.0". */
    private String license;
    private String licenseUrl;
    /** The page the audio was taken from. */
    private String sourceUrl;

    public Attribution() {}

    public Attribution(String title, String author, String license, String licenseUrl, String sourceUrl) {
        this.title = title;
        this.author = author;
        this.license = license;
        this.licenseUrl = licenseUrl;
        this.sourceUrl = sourceUrl;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public String getLicenseUrl() { return licenseUrl; }
    public void setLicenseUrl(String licenseUrl) { this.licenseUrl = licenseUrl; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    /** One credit line: "Title" by Author - License (license link) - source link. */
    public String creditLine() {
        StringBuilder b = new StringBuilder();
        if (title != null && !title.isBlank()) b.append('"').append(title).append('"');
        if (author != null && !author.isBlank()) b.append(b.isEmpty() ? "" : " ").append("by ").append(author);
        if (license != null && !license.isBlank()) {
            b.append(b.isEmpty() ? "" : " - ").append(license);
            if (licenseUrl != null && !licenseUrl.isBlank()) b.append(" (").append(licenseUrl).append(')');
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) b.append(b.isEmpty() ? "" : " - ").append(sourceUrl);
        return b.toString();
    }

    @Override public boolean equals(Object o) {
        return o instanceof Attribution a && Objects.equals(title, a.title) && Objects.equals(author, a.author)
            && Objects.equals(license, a.license) && Objects.equals(licenseUrl, a.licenseUrl)
            && Objects.equals(sourceUrl, a.sourceUrl);
    }

    @Override public int hashCode() {
        return Objects.hash(title, author, license, licenseUrl, sourceUrl);
    }

    @Override public String toString() { return creditLine(); }
}
