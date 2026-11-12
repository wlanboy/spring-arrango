package com.example.arrango.model.k8s;

/** Eine Revision eines Helm-Releases, eingebettet in {@link HelmRelease#getHistory()}. */
public class HelmRevision {

	private int revision;

	private String status;

	private String chart;

	private String chartVersion;

	private String appVersion;

	/** {@code creationTimestamp} des Release-Secrets, also der Zeitpunkt von install/upgrade/rollback. */
	private String deployedAt;

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

	public String getDeployedAt() {
		return deployedAt;
	}

	public void setDeployedAt(String deployedAt) {
		this.deployedAt = deployedAt;
	}
}
