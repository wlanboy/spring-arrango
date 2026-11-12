package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/**
 * Helm-Release (aus dem Release-Secret {@code sh.helm.release.v1.<name>.v<rev>}). Die Felder beschreiben die
 * neueste Revision; {@link #getHistory()} sammelt alle Revisionen, die ein Import je gesehen hat, auch wenn Helm
 * die alten Secrets inzwischen gelöscht hat.
 */
@Document("helm_releases")
public class HelmRelease extends K8sResource {

	private String chart;

	private String chartVersion;

	private String appVersion;

	private int revision;

	private String status;

	/** Alle bekannten Revisionen, neueste zuerst. */
	private List<HelmRevision> history;

	public HelmRelease() {
	}

	public HelmRelease(String namespace, String name) {
		super("HelmRelease", namespace, name);
	}

	public String getChart() {
		return chart;
	}

	public void setChart(String chart) {
		this.chart = chart;
	}

	public String getChartVersion() {
		return chartVersion;
	}

	public void setChartVersion(String chartVersion) {
		this.chartVersion = chartVersion;
	}

	public String getAppVersion() {
		return appVersion;
	}

	public void setAppVersion(String appVersion) {
		this.appVersion = appVersion;
	}

	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public List<HelmRevision> getHistory() {
		return history;
	}

	public void setHistory(List<HelmRevision> history) {
		this.history = history;
	}
}
