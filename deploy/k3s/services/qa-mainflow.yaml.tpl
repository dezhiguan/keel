apiVersion: apps/v1
kind: Deployment
metadata:
  name: PLACEHOLDER_AGENT
  namespace: agents
  labels: {app: PLACEHOLDER_AGENT, keel.io/agent: PLACEHOLDER_AGENT, keel.io/env: PLACEHOLDER_QA_ENV}
spec:
  replicas: 1
  selector:
    matchLabels: {app: PLACEHOLDER_AGENT}
  template:
    metadata:
      labels: {app: PLACEHOLDER_AGENT, keel.io/agent: PLACEHOLDER_AGENT, keel.io/env: PLACEHOLDER_QA_ENV}
    spec:
      automountServiceAccountToken: false
      imagePullSecrets: [{name: acr-cred}]
      hostAliases:
        - ip: "172.25.90.183"
          hostnames: ["jp.cloud.langfuse.com"]
      containers:
        - name: agent
          image: PLACEHOLDER_QA_MAINFLOW_IMAGE
          envFrom:
            - secretRef: {name: keel-PLACEHOLDER_AGENT}
          env:
            - name: KEEL_AGENT_NAME
              value: PLACEHOLDER_AGENT
            - name: KEEL_TRACE_BUFFER_PATH
              value: /tmp/keel-traces
          ports: [{containerPort: 8000}]
          volumeMounts:
            - {name: manifest, mountPath: /app/agent.yaml, subPath: agent.yaml, readOnly: true}
            - {name: data, mountPath: /app/.keel}
          readinessProbe:
            httpGet: {path: /v1/health, port: 8000}
            initialDelaySeconds: 5
            periodSeconds: 5
          resources:
            requests: {cpu: 100m, memory: 256Mi}
            limits: {cpu: 1000m, memory: 768Mi}
      volumes:
        - name: manifest
          configMap: {name: qa-mainflow-PLACEHOLDER_AGENT-manifest}
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: PLACEHOLDER_AGENT
  namespace: agents
spec:
  selector: {app: PLACEHOLDER_AGENT}
  ports:
    - {port: 8000, targetPort: 8000}
