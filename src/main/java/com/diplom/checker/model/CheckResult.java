package com.diplom.checker.model;

import java.util.List;

public class CheckResult {
    private String font;
    private int fontSize;
    private int wordCount;
    private List<String> mistakes;
    private List<String> brokenLinks;

    public CheckResult(String font,
                       int fontSize,
                       int wordCount,
                       List<String> mistakes,
                       List<String> brokenLinks) {
        this.font = font;
        this.fontSize = fontSize;
        this.wordCount = wordCount;
        this.mistakes = mistakes;
        this.brokenLinks = brokenLinks;
    }

    public String getFont() { return font; }
    public void setFont(String font) { this.font = font; }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; }

    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }

    public List<String> getMistakes() { return mistakes; }
    public void setMistakes(List<String> mistakes) { this.mistakes = mistakes; }

    public List<String> getBrokenLinks() { return brokenLinks; }
    public void setBrokenLinks(List<String> brokenLinks) { this.brokenLinks = brokenLinks; }
}
