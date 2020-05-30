# CloudBees CI (Core) Modern -- Example of Creating/Updating Managed Masters Programmatically

Note, this only works on the latest version of 2.222.4.3. This script should be run against the Operations Center. It can be run from the script console, the jenkins cli, or REST.

## Prereqs

1. CloudBees RBAC enabled, and that you have external groups named `app001, and app002`.
2. Ensure the `mm-custom-groovy` config map is created in the same namespace as the master. This is mounted to the master and contains the groovy to provision rbac based on the convention of the APP_NUMBERS env var defining the list of app ids.

