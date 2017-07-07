import {spawn} from 'child_process';
import * as chalk from 'chalk';
import {Cloud} from './cloud';
import {ProjectUtils} from '../common/utils';
import {Options} from '../common/options';

export class LocalMachine implements Cloud {

  constructor(private options: Options) {
  }

  startExperiment(): void {

    // Build project
    ProjectUtils.buildProject()
      .then(jarDir => {

        const spawnOptions = {
          stdio: 'inherit',
          cwd: jarDir
        };

        // First start the  tracker node
        console.info(chalk.bold.green('> Start Tracker node...'));
        const tracker = spawn('java', ['-jar', ProjectUtils.JAR_NAME, 'tracker'], {
          ...spawnOptions, env: {
            NODES: this.options.nodes,
            DURATION: this.options.duration,
            EXPERIMENTS: this.options.experiments,
            INITIAL_SEED: this.options.initialSeed,
            REPORT_PATH: this.options.reportPath
          }
        });

        // Start nodes with a delay
        let i = 0;
        console.info(chalk.bold.green('> Start Nodes every 2sec...'));
        const interval = setInterval(() => {
          if (i >= this.options.nodes) {
            clearInterval(interval);
          } else {
            i++;
            console.info(chalk.bold.green(`> Start Node [${i}]...`));
            spawn('java', ['-jar', ProjectUtils.JAR_NAME, 'node', '127.0.0.1', '10000'], {
              ...spawnOptions, env: {ID: i, PORT: (10000 + i)}
            });
          }
        }, 2000);

      })
      .catch(() => process.exit(-1));
  }

  shutdown(): void {
    console.log(chalk.yellow('Nodes cannot be shutdown in local.'));
    process.exit(-1);
  }

  downloadReport(): void {
    console.log(chalk.yellow('Cannot download report in local.'));
    process.exit(-1);
  }

  watchTrackerLogs(): void {
    console.log(chalk.yellow('Cannot watch tracker logs in local.'));
    process.exit(-1);
  }
}