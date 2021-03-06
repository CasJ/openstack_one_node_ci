#!/usr/bin/env groovy

def deploy_openstack(release = 'master') {

    echo 'Deploying OpenStack All In One'
    git branch: release, url: 'https://github.com/openstack/openstack-ansible'
    sh '''
    export apply_security_hardening=false
    sudo scripts/bootstrap-ansible.sh
    sudo scripts/bootstrap-aio.sh
    sudo scripts/run-playbooks.sh
    cd playbooks/
    sudo openstack-ansible os-tempest-install.yml
    '''

}

def configure_tempest() {

    echo 'Installing and configuring Tempest'
    // Install latest version of Tempest in the host
    sh '''
    git clone https://github.com/openstack/tempest.git $HOME/tempest
    sudo pip install $HOME/tempest/
    '''

    // Get a config file template with the basic static values of an OSA deployment
    sh '''
    cd $HOME/tempest/etc/
    wget https://raw.githubusercontent.com/CasJ/openstack_one_node_ci/master/tempest.conf
    '''

    // Get the tempest config file generated by the OSA deployment
    echo 'Configuring tempest based on the ansible deployment'
    sh '''
    container_name=$(sudo lxc-ls -f | grep aio1_utility_container- | awk '{print $1}')
    sudo cp /var/lib/lxc/$container_name/rootfs/opt/tempest_untagged/etc/tempest.conf $HOME/tempest/etc/tempest.conf.osa
    '''

    // Configure the dynamic values of tempest.conf based on the OSA deployment
    sh '''
    keys='admin_password image_ref image_ref_alt uri uri_v3 public_network_id'
    for key in $keys
    do
        a="${key} ="
        sed -ir "s|$a.*|$a|g" $HOME/tempest/etc/tempest.conf
        b=`cat $HOME/tempest/etc/tempest.conf.osa | grep "$a"`
        sed -ir "s|$a|$b|g" $HOME/tempest/etc/tempest.conf
    done
    '''

    // Initialize testr and create a dir to store results
    sh '''
    mkdir -p $HOME/subunit
    cd $HOME/tempest/
    testr init
    '''

}

def run_tempest_smoke_tests(results_file = 'results', elasticsearch_ip = null, host_ip = null) {

    String newline = "\n"
    def tempest_output, failures

    // Run the tests and store the results in ~/subunit/before    
    tempest_output = sh returnStdout: true, script: """
    cd \$HOME/tempest/
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke || echo 'Some smoke tests failed.'
    mkdir -p \$HOME/subunit/smoke
    cp .testrepository/\$stream_id \$HOME/subunit/smoke/${results_file}
    """

    // Make sure there are no failures in the smoke tests, if there are stop the workflow
    println tempest_output
    if (tempest_output.contains('- Failed:') == true) {

	failures = tempest_output.substring(tempest_output.indexOf('- Failed:') + 10)
        failures = failures.substring(0,failures.indexOf(newline)).toInteger()
        if (failures > 1) {
	    println 'Parsing failed smoke'
            if (elasticsearch_ip != null && host_ip != null ) {
	        aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip)
            }
            error "${failures} tests from the Tempest smoke tests failed, stopping the pipeline."
        } else {
            println 'The Tempest smoke tests were successfull.'
        }

    } else {

        error 'There was an error running the smoke tests, stopping the pipeline.'

    }

}

def rolling_upgrade(to_release = 'master') {

    git 'https://github.com/osic/qa-jenkins-onmetal.git'
    sh """
    sudo ansible-playbook prepare_for_upgrade.yaml -i localhost, -e openstack_release=${to_release}
    """

}

def install_persistent_resources_tests() {

    // Install Persistent Resources tests on the onMetal host
    echo 'Installing Persistent Resources Tempest Plugin'
    sh '''
    rm -rf $HOME/persistent-resources-tests
    git clone https://github.com/osic/persistent-resources-tests.git $HOME/persistent-resources-tests
    sudo pip install --upgrade $HOME/persistent-resources-tests/
    '''

}

def run_persistent_resources_tests(action = 'verify', results_file = null) {

    String file_name

    if (results_file == null) {
        results_file = action
    }

    sh """
    cd \$HOME/tempest/
    stream_id=`cat .testrepository/next-stream`
    sudo ostestr --regex persistent-${action}  || echo 'Some persistent resources tests failed.'
    sudo chown -R ubuntu:ubuntu .testrepository 
    mkdir -p \$HOME/subunit/persistent_resources/
    cp .testrepository/\$stream_id \$HOME/subunit/persistent_resources/${results_file}
    """

}

def install_during_upgrade_tests() {

     // Setup during tests
    sh '''
    mkdir -p $HOME/output
    rm -rf $HOME/rolling-upgrades-during-test
    git clone https://github.com/osic/rolling-upgrades-during-test $HOME/rolling-upgrades-during-test
    '''

}

def start_during_test() {

    // Run during test
    sh '''
    cd $HOME/rolling-upgrades-during-test
    sudo python call_test.py -d &
    ''' 

}

def stop_during_test() {

    // Stop during test by creating during.uptime.stop
    sh '''
    sudo touch /usr/during.uptime.stop
    '''
    // Wait up to 10 seconds for the results file gets created by the script
    sh '''
    x=0
    while [ "$x" -lt 100 -a ! -e $HOME/output/during.uptime.out ]; do
        x=$((x+1))
        sleep .1
    done
    '''

}

def install_api_uptime_tests() {

    // setup api uptime tests
    sh '''
    mkdir -p $HOME/output
    rm -rf $HOME/api_uptime
    git clone https://github.com/osic/api_uptime.git $HOME/api_uptime
    cd $HOME/api_uptime
    sudo pip install --upgrade -r requirements.txt
    '''

}

def start_api_uptime_tests() {

    sh '''
    sudo rm -f /usr/api.uptime.stop
    cd $HOME/api_uptime/api_uptime
    python call_test.py -v -d -s nova,swift -o $HOME/output/api.uptime.out &
    ''' 

}

def stop_api_uptime_tests() {

    // stop the API uptime tests
    sh '''
    sudo touch /usr/api.uptime.stop
    '''
    // Wait up to 10 seconds for the results file gets created by the script
    sh '''
    x=0
    while [ "$x" -lt 100 -a ! -e $HOME/output/api.uptime.out ]; do
        x=$((x+1))
        sleep .1
    done
    '''

}

def setup_parse_persistent_resources() {
    
    sh '''
    rm -rf $HOME/persistent-resources-tests-parse
    git clone https://github.com/osic/persistent-resources-tests-parse.git $HOME/persistent-resources-tests-parse
    sudo pip install --upgrade $HOME/persistent-resources-tests-parse/
    '''

}

def parse_persistent_resources_tests() {
    
    sh '''
    mkdir -p $HOME/output
    cd $HOME/subunit/persistent_resources/
    resource-parse --u . > $HOME/output/persistent_resource.txt
    rm *.csv
    '''

}

def aggregate_results(host_ip) {

    //Pull persistent, during, api, smoke results from onmetal to ES vm
    sh """
    {
        scp -o StrictHostKeyChecking=no -r ubuntu@${host_ip}:\$HOME/output/ \$HOME
    } || {
        echo 'No output directory found.'
    }
    {
        scp -o StrictHostKeyChecking=no -r ubuntu@${host_ip}:\$HOME/subunit/ \$HOME
    } || {
        echo 'No subunit directory found.'
    }
    """

}

def parse_results() {
	
    sh '''
    elastic-upgrade -u $HOME/output/api.uptime.out -d $HOME/output/during.uptime.out -p $HOME/output/persistent_resource.txt -b $HOME/subunit/smoke/before_upgrade -a $HOME/subunit/smoke/after_upgrade
    elastic-upgrade -s $HOME/output/nova_status.json,$HOME/output/swift_status.json,$HOME/output/keystone_status.json
    rm -rf $HOME/output $HOME/subunit
    '''

}

def aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip) {

    //Pull persistent, during, api, smoke results from host to ES vm
    sh """
    ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
    {
        scp -o StrictHostKeyChecking=no -r ubuntu@${host_ip}:\$HOME/output/ \$HOME
    } || {
        echo 'No output directory found.'
    }
    {    
        scp -o StrictHostKeyChecking=no -r ubuntu@${host_ip}:\$HOME/subunit/ \$HOME
    } || {
        echo 'No subunit directory found.'
    }
    '''
    """

	if (results_file == 'after_upgrade') {
	    //Pull persistent, during, api, smoke results from onmetal to ES 
	    sh """
            ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
	    elastic-upgrade -u \$HOME/output/api.uptime.out -d \$HOME/output/during.uptime.out -p \$HOME/output/persistent_resource.txt -b \$HOME/subunit/smoke/before_upgrade -a \$HOME/subunit/smoke/after_upgrade
            elastic-upgrade -s \$HOME/output/nova_status.json,\$HOME/output/swift_status.json,\$HOME/output/keystone_status.json
	    ''''
	    """
	}
	else {
	    sh """
            ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
	    elastic-upgrade -b \$HOME/subunit/smoke/before_upgrade
	    '''
	    """
	}

}

def install_parser() {

    sh '''
    rm -rf $HOME/elastic-benchmark
    git clone https://github.com/osic/elastic-benchmark $HOME/elastic-benchmark
    sudo pip install --upgrade $HOME/elastic-benchmark/
    '''

}

def cleanup_test_results() {

    println "Removing previous test results files..."
    sh '''
    find $HOME/subunit ! -name '.*' ! -type d -exec rm -- {} + || echo "No subunit directory found."
    find $HOME/output ! -name '.*' ! -type d -exec rm -- {} + || echo "No output directory found."
    '''
    println "The environment is clean from previous test results."

}


// The external code must return it's contents as an object
return this;
