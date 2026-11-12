package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** Helm-Release (aus dem Release-Secret {@code sh.helm.release.v1.<name>.v<rev>}). */
@Document("helm_releases")
public class HelmRelease extends K8sResource {

	private String chart;

	private String chartVersion;

	private String appVersion;

	private int revision;

	private String status;

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
}
