// SPDX-License-Identifier: AGPL-3.0-only
package main

import (
	"bufio"
	"fmt"
	"os"

	"bucc.local/physical-proxy"
)

func main() {
	session := physicalproxy.NewPhysicalProxySession()
	defer session.Disconnect()
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 2<<20)
	out := bufio.NewWriter(os.Stdout)
	for scanner.Scan() {
		fmt.Fprintln(out, session.Exchange(scanner.Text()))
		if out.Flush() != nil {
			return
		}
	}
}
