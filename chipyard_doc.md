## Chipyard and FireSim

Set a Chipyard path for eaiser copy and paste later on:
```bash
export CHIPYARD_DIR=~/chipyard  # or wherever you cloned the Chipyard repo
```
This sets the `$CHIPYARD_DIR` environment variable to your Chipyard root directory, so you can just copy and paste the commands later on.

---

### Baremetal RISC-V
Convert C code file into RISC-V. Replace \<FILE> with the file name and run this command in the same directory as the C file. [More information here.](https://chipyard.readthedocs.io/en/stable/Software/Baremetal.html)
```bash
riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c <FILE>.c

riscv64-unknown-elf-gcc -static -specs=htif_nano.specs <FILE>.o -o <FILE>.riscv
```

---

### RTL Simulation

To test a RISC-V file run:
```bash
spike <FILE>.riscv
```
To run RISC-V code and simulate a Config enter the Verilator directory and run the make command:
```bash
cd $CHIPYARD_DIR/sims/verilator

make CONFIG=<RocketConfig> run-binary BINARY=<FILE_PATH>.riscv
```

Rocket Config Classes can be found in `$CHIPYARD_DIR/generators/chipyard/src/main/scala/config`.

Example:
```bash
make CONFIG=RocketPrefetchTemplate run-binary BINARY=~/chipyard/tests/NL_Calcs_8k.riscv
```

> *If it keeps failing due to looking in* `/bin/bash/` *run the following then try again:*
> 
> ```bash
> export PATH=~/chipyard/.conda-env/bin:$PATH
> 
> make clean
> ```

More information on [the Chipyeard documentation](https://chipyard.readthedocs.io/en/stable/Simulation/Software-RTL-Simulation.html).

-----

### FireSim

#### Initialization

This section will go over how to setup firesim.

_If you are not added to the firesim Linux group, ask to be added._

-----

First you must add `create_db_2023.1.tcl`, `implementation_2023.1.tcl`, and `implementation_idr__2023.1.tcl` into the following directory:

> `$CHIPYARD_DIR/sims/firesim/platforms/xilinx_avelo_u250/cl_firesim/scripts/` 

These files can be found in the same github repo as this documentaion.

Next, complete the following [Non-Sudo Setup](https://docs.fires.im/en/latest/Local-FPGA-Initial-Setup.html#non-sudo-setup) steps of the Firesim documentation:

```bash
cd ~/.ssh 
ssh-keygen -t ed25519 -C "firesim.pem" -f firesim.pem
[create passphrase]
```
```bash
cd ~/.ssh
cat firesim.pem.pub >> authorized_keys
chmod 0600 authorized_keys
```
The steps till this point only need to be completed once.

-----

***The following steps need to be completed each time FireSim is used:***

```bash
cd ~/.ssh
ssh-agent -s > AGENT_VARS
source AGENT_VARS
ssh-add firesim.pem
```

Enter the FireSim directory and source the FireSim enviornment:
```bash
cd $CHIPYARD_DIR/sims/firesim
source sourceme-manager.sh --skip-ssh-setup
```

<br>

If everything else is setup skip to [Running FireSim](#running-firesim), else continue.

<br>

> This warning can be ignored:
> ```
> CONDA_BACKUP_CPPFLAGS=-DNDEBUG -D_FORTIFY_SOURCE=2 -O2 -isystem /home/user/miniforge3/include -DNDEBUG -D_FORTIFY_SOURCE=2 -O2 -isystem /home/user/miniforge3/include
> ::WARNING:: you still seem to have -DNDEBUG in your environment. This is known to cause problems.
> ```

-----

#### Config Setup

This section will go over how to set up files for firesim simulation.

Ensure the Rocket Config that is being simulated is in `$CHIPYARD_DIR/generators/firechip/src/main/scala/TargetConfigs.scala`.

If missing, create a new class with this format:
```scala
class FireSim<Config_Name> extends Config(
    new WithNIC ++
    new WithDefaultFireSimBridges ++
    new WithDefaultMemModel ++
    new WithFireSimConfigTweaks ++
    new chipyard.<Config_Name>
)
```
> The <Config_Name> must match the class name in your config file.

-----

Next add this config in `$CHIPYARD_DIR/sims/firesim/deploy/config_build_recipes.yaml`. 
Make sure to change TargetConfigName to the correct TargetConfig class. 

```yaml
alveo_u250_firesim_TargetConfigName:   # Change based on fpga and TargetConfig class
    DESIGN: FireSim
    PLATFORM: xilinx_alveo_u250        # The fpga you want to run it on
    PLATFORM_CONFIG: WithPrintfSynthesis_BaseXilinxAlveoConfig    # The underscore _ seperates multiple classes added to the processor
    TARGET_CONFIG: TargetConfigName    # This should be the same name of the class just created in TargetConfigs.scala
    TARGET_PROJECT: firesim
    bit_builder_recipe: bit-builder-recipes/xilinx_alveo_u250.yaml  # u250 corresponds to the u250 fpga. Can change to u200 or u280 depending on which fpga is wanted.
    deploy_quintuplet: null
    metasim_customruntimeconfig: null
    platform_config_args:
        fpga_frequency: 60  # MHz
        build_strategy: TIMING
    post_build_hook: null
```

-----

Open `$CHIPYARD_DIR/sims/firesim/deploy/config_build.yaml` and add the config_build_recipe name under `builds_to_run:` as shown in the example below:

```yaml
builds_to_run:
  - alveo_u250_firesim_TargetConfigName
```
Under the `build_farm_hosts:` section lists the various hosts. The following hosts are defined on draccus. `default_build_dir:` points to the directory of the builds.

```yaml
build_farm_hosts:
- localhost         # The number of hosts needed is equal to number of builds under builds_to_run:
- build farm host0
- build_farm_host1
- build_farm_host2
- build_farm_host3
- build_farm_host4
- build_farm_host5
- build_farm_host6
- build_farm_host7
- build_farm_host8
- build_farm_host9
- build_farm_host10
- build_farm_host11
- build_farm_host12
default_build_dir: /home/user/chipyard/sims/firesim/builds # Change this based on your chipyard directory
```
-----

#### Running FireSim

If you haven't sourced the firesim enviornment yet, finish the [Initailization](#initialization) steps now.

To build what was just setup run,
```bash
firesim buildbitstream
```

> If encountering errors look at the [known buildbitstream errors.](#build-bitstream-errors)

After running this, it should output a message like the following:

```
Add

alveo_u250_firesim_TargetConfigName:
    bitstream_tar: <some link>
    ...
  
to your config_hwdb.yaml to use this hardware configuration.
```
As it says, paste these lines into `$CHIPYARD_DIR/sims/firesim/deploy/config_hwdb.yaml` (Only the lines between "Add" and "to your ...").

<!-- `agfi` will be used if running on Amazon cloud servers. -->

-----

Go to `$CHIPYARD_DIR/sims/firesim/deploy/config_runtime.yaml` and change the `default_hw_config` to the same config_build name like the example below.

```yaml
target _config:
  default_hw_config: alveo_u250_firesim_TargetConfigName # or whatever you added to config_hwdb.yaml.
```

`workload_name:` is used to switch between linux or bare-metal. 
Most of the time leave as `linux-uniform.json`.

-----

Now run
```bash
firesim infrasetup
```
This will copy the tar file and flash the fpga. 
Then run `firesim boot` (simulatates in the background) or `firesim runworkload` (shows simulation progress) to start simulation.

Running will create a screen session to go into:
```bash
screen -r fsim0   # This increaments (fsim1, fsim2, ...) based on how many simulations are running.
```

-----

### Known Errors

#### Build Bitstream Errors

- **Null build directory error when running `firesim buildbitstream`.**
  ```
  $ firesim buildbitstream
  ...
  Exception: ERROR: Invalid null build dir
  ...
  ```
  FIX: Make sure `default_build_dir:` points to the correct directory in `config_build.yaml`.

  <br>

- **Vivado not found.**

  ```bash
  ... : line 75: vivado: command not found
  FireSim Xilinx Alveo xilinx_alveo_u250 FPGA Build Failed
  Your FPGA build failed for quintuplet: xilinx_alveo_u250-firesim-FireSim-FireSimRocketNLPrefetchWithAccuracy-WithPrintfSynthesis_BaseXilinxAlveoConfig
  ERROR: A bitstream build failed.
  Fatal error.
  ```

  FIX: 
  Add this line to the top of `~/.bashrc`:
  ```bash
  source /opt/Xilinx/Vivado/2023.1/settings64.sh
  ```
  Then check that the version is returned by running these commands:
  
  ```bash
  echo 'source ~/.bashrc' >> ~/.bash_profile

  source ~/.bashrc

  ssh localhost which vivado # a path should be returned
  ```
  Now source the firesim enviornment again:
  ```bash
  cd $CHIPYARD_DIR/sims/firesim
  source sourceme-manager.sh --skip-ssh-setup
  ```
  This only needs to be once to fix this issue.

<br>

- **Cannot find file `create_db_2023.1.tcl`.**
  
  You did not add `create_db_2023.1.tcl`, `implementation_2023.1.tcl`, and `implementation_idr__2023.1.tcl` into `$CHIPYARD_DIR/sims/firesim/platforms/xilinx_avelo_u250/cl_firesim/scripts/`.

  See the [Initalization Steps](#initialization).

  <br>

- **Other possible solutions to `firesim buildbitstream` errors**.

  Ensure `TARGET_CONFIG:` has correct name in `config_build_recipes.yaml`. It should match the class name in `TargetConfigs.scala`. Make sure `TARGET_PROJECT: firesim`.

  <br>


- **Current Issue:**

  Unable to find `libdwarf.so.1`

  Current Changes:

  ```bash
  conda install -c conda-forge libdwarf

  export LD_LIBRARY_PATH=$CONDA_PREFIX/lib:$LD_LIBRARY_PATH
  ```


-----

#### Quick Commands

```bash
export CHIPYARD_DIR=~/chipyard
cd ~/.ssh
ssh-agent -s > AGENT_VARS
source AGENT_VARS
ssh-add firesim.pem
```
```bash
cd $CHIPYARD_DIR/sims/firesim
source sourceme-manager.sh --skip-ssh-setup
unset CONDA_BACKUP_CPPFLAGS
```



