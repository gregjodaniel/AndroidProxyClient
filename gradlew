#!/bin/sh

# Set default values for variables
APP_HOME=$(cd "$(dirname "$0")" && pwd)
exec gradle "$@"
