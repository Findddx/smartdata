package trivy

# VITE_DEMO_AUTH is a public compile-time boolean that enables local demo identities.
# It is not a password or bearer token. Suppress only these two exact instructions;
# changing the ARG value or adding any other secret instruction still fails DS-0031.
# Vulnerability findings and secret-scanner findings can never match this policy.
default ignore = false

demo_flag_instructions = {
    "Possible exposure of secret env \"VITE_DEMO_AUTH\" in ARG": "ARG VITE_DEMO_AUTH=true",
    "Possible exposure of secret env \"VITE_DEMO_AUTH\" in ENV": "ENV VITE_DEMO_AUTH=${VITE_DEMO_AUTH}"
}

ignore {
    input.Type == "misconfiguration"
    input.ID == "DS-0031"
    input.Namespace == "builtin.dockerfile.DS031"
    input.CauseMetadata.Provider == "Dockerfile"
    count(input.CauseMetadata.Code.Lines) == 1
    input.CauseMetadata.Code.Lines[0].Content == demo_flag_instructions[input.Message]
}
