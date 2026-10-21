package com.saptarishi.backend.graph;

@FunctionalInterface
public interface ProgressListener {

    void update(String stage, int percent);
}