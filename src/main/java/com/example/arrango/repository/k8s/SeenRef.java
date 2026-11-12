package com.example.arrango.repository.k8s;

/** Ressource mit den Zeitstempeln des ersten und letzten Imports, der sie gesehen hat. */
public record SeenRef(String id, String kind, String namespace, String name, String firstSeen, String lastSeen) {
}
